-- =============================================================================
-- Hàng tặng có còn trong kho không
--
-- A promotion can be earned and still not be deliverable. `CalcPromo` says the
-- customer has won a case of Pepsi; the depot says there is no Pepsi. The legacy
-- checks this before the order leaves the screen — `CheckQtyAvail4AddFreeItem`,
-- reporting through `SitePromotionNotEnough`, which carries the quantity wanted
-- and the quantity actually available.
--
-- Note what it does *not* do: it does not silently reduce the gift. The rep is
-- told, and the promise they make to the customer is theirs to make. Quietly
-- writing one case where two were earned would have the rep find out from the
-- shop, which is the worst possible place.
--
-- Three subtractions make the number honest, all of them in the legacy:
--
--   * the warehouse's own quantity to begin with;
--   * minus what this order already *sells* of the same product — stock cannot
--     be given away twice, and a basket of twenty cases has already claimed them;
--   * minus what earlier promotions on this same order have already promised,
--     which is what `_lstFreeItemQtyAvailUsedPromotion` caches per site and item.
--
-- Left out deliberately: lots and expiry dates, warehouse locations, and the
-- site-type fallback that goes looking for another depot. This schema has one
-- quantity per product per site, and inventing three more levels of it would be
-- modelling a warehouse nobody here has.
-- =============================================================================

create or replace function calculate_promotions(
    p_customer_id uuid,
    p_lines       jsonb,
    p_order_date  date default current_date
)
returns jsonb
language plpgsql
security invoker
set search_path = public, pg_temp
as $$
declare
    v_seq        record;
    v_break      record;
    v_cond       record;
    v_p          integer;
    v_available  integer;
    v_basis      numeric;   -- the money the reward is computed against
    v_group_qty  numeric;
    v_group_amt  numeric;
    v_order_amt  numeric;   -- the order as the customer built it, reported back
    v_order_left numeric;   -- what order-scope levels have not yet consumed
    v_amount     numeric;
    v_percent    numeric;
    v_earned     jsonb := '[]'::jsonb;
    v_free       jsonb;
    v_total      numeric := 0;
    v_fired      boolean;
    v_vetoed     uuid[] := '{}';
    v_lots       integer;   -- complete sets of the rule's required items
    v_sub_qty    numeric;   -- the secondary list's quantity
    v_sub_amt    numeric;   -- the secondary list's money
    v_measure    numeric;   -- what the level's primary threshold is tested on
    v_site       uuid;      -- the warehouse the gifts would come out of
    v_short      jsonb := '[]'::jsonb;
begin
    -- Three carts and a tally, because the flags make one insufficient.
    --
    --   _cart_base   the basket as the customer built it, never written to
    --   _cart_shared the running remainder, eaten by every rule that fires
    --   _cart        the copy the rule being evaluated works on
    --   _used        what this rule ate, so the pool can be reduced by the delta
    --   _free_stock  what the depot can still give away, per product
    --
    -- Session-scoped rather than transaction-scoped on purpose: a temp table
    -- dropped at every commit invalidates the cached plans that reference it,
    -- and the second call in a pooled connection then fails on a relation that
    -- no longer exists. Created once, emptied per call.
    create temporary table if not exists _cart (
        product_id uuid,
        uom_code   text,
        qty        numeric,
        amount     numeric
    );

    create temporary table if not exists _cart_base (
        product_id uuid,
        uom_code   text,
        qty        numeric,
        amount     numeric
    );

    create temporary table if not exists _cart_shared (
        product_id uuid,
        uom_code   text,
        qty        numeric,
        amount     numeric
    );

    create temporary table if not exists _used (
        product_id uuid,
        uom_code   text,
        qty        numeric,
        amount     numeric
    );

    create temporary table if not exists _free_stock (
        product_id uuid,
        qty_base   numeric
    );

    -- `where true` rather than a bare delete: the pooled connection PostgREST
    -- uses refuses an unqualified DELETE, and a temp table is no exception.
    delete from _cart_base where true;

    insert into _cart_base (product_id, uom_code, qty, amount)
    select product_id, uom_code, qty, amount
    from cart_in_all_units(p_customer_id, p_order_date, p_lines);

    delete from _cart_shared where true;
    insert into _cart_shared (product_id, uom_code, qty, amount)
    select product_id, uom_code, qty, amount from _cart_base;

    -- The warehouse the gifts would be picked from. The same rule the stock
    -- screen uses, so the rep sees one number in two places rather than two.
    select s.id into v_site
    from site s
    where s.is_active
    order by s.code
    limit 1;

    -- What the depot can still give away: its own quantity, less what this order
    -- already sells of the same product. Stock cannot be given away twice.
    delete from _free_stock where true;

    insert into _free_stock (product_id, qty_base)
    select
        ss.product_id,
        greatest(
            ss.qty_base - coalesce(sold.base_qty, 0),
            0
        )
    from site_stock ss
    left join lateral (
        select sum((l ->> 'qty')::numeric * pu.conversion_rate) as base_qty
        from jsonb_array_elements(coalesce(p_lines, '[]'::jsonb)) as l
        join product_uom pu
            on pu.product_id = (l ->> 'product_id')::uuid
           and pu.uom_code = (l ->> 'uom_code')::text
        where (l ->> 'product_id')::uuid = ss.product_id
    ) sold on true
    where ss.site_id = v_site;

    -- The order total is the cart at its own units, before any rule touches it:
    -- an order-level threshold is written against what the customer is spending,
    -- not against what is left after the line rules have eaten into it.
    select coalesce(sum((l ->> 'qty')::numeric * pr.price), 0)
    into v_order_amt
    from jsonb_array_elements(coalesce(p_lines, '[]'::jsonb)) as l
    join customer c on c.id = p_customer_id
    left join lateral (
        select pl.price
        from price_list pl
        where pl.product_id = (l ->> 'product_id')::uuid
          and pl.uom_code = (l ->> 'uom_code')::text
          and (pl.class_id = c.class_id or pl.class_id is null)
          and p_order_date between pl.from_date and pl.to_date
        order by pl.class_id nulls last
        limit 1
    ) pr on true;

    -- The running copy. The reported total stays what the customer is spending.
    v_order_left := v_order_amt;

    for v_seq in
        select * from applicable_discount_sequences(p_customer_id, p_order_date)
    loop
        -- Vetoed by a rule that already fired.
        if v_seq.sequence_id = any (v_vetoed) then
            continue;
        end if;

        -- Which basket this rule is measured against. The shared pool is what
        -- earlier rules have left; the base is what the customer actually bought.
        delete from _cart where true;
        insert into _cart (product_id, uom_code, qty, amount)
        select product_id, uom_code, qty, amount
        from (
            select * from _cart_shared where v_seq.deducts_shared
            union all
            select * from _cart_base where not v_seq.deducts_shared
        ) src;

        delete from _used where true;
        v_fired := false;

        -- Complete sets of the required items. GetMinGroupLot: the minimum, not
        -- the sum, because a set is only complete when every part of it is there.
        -- Items with no required value are in the group but impose no minimum.
        v_lots := 0;

        if v_seq.required_type <> 'none' then
            select coalesce(
                min(
                    floor(
                        case when v_seq.required_type = 'amount' then c.amount else c.qty end
                        / ci.required_value
                    )
                ),
                0
            )::integer
            into v_lots
            from discount_condition_item ci
            join _cart c
                on c.product_id = ci.product_id and c.uom_code = ci.uom_code
            where ci.sequence_id = v_seq.sequence_id
              and ci.required_value > 0;

            -- A rule that names required items and does not find a complete set
            -- earns nothing, whatever else is in the basket.
            if coalesce(v_lots, 0) < 1 then
                continue;
            end if;
        end if;

        -- The secondary list's totals, which every level of this rule is also
        -- tested against.
        v_sub_qty := 0;
        v_sub_amt := 0;

        if v_seq.sub_break_type <> 'none' then
            select coalesce(sum(c.qty), 0), coalesce(sum(c.amount), 0)
            into v_sub_qty, v_sub_amt
            from discount_sub_break_item si
            join _cart c
                on c.product_id = si.product_id and c.uom_code = si.uom_code
            where si.sequence_id = v_seq.sequence_id;
        end if;

        -- Group rules pool their condition items before looking at any level.
        if v_seq.scope = 'group' then
            select coalesce(sum(c.qty), 0), coalesce(sum(c.amount), 0)
            into v_group_qty, v_group_amt
            from discount_condition_item ci
            join _cart c
                on c.product_id = ci.product_id and c.uom_code = ci.uom_code
            where ci.sequence_id = v_seq.sequence_id;
        end if;

        for v_break in
            select * from discount_break
            where sequence_id = v_seq.sequence_id
            -- The secondary test is a gate, not a ranking: a level whose sub
            -- threshold the basket misses is not a lesser level, it is no level.
              and (
                v_seq.sub_break_type = 'none'
                or (
                    case v_seq.sub_break_type
                        when 'amount' then
                            sub_break_amt <= v_sub_amt
                            and (sub_break_amt_upper = 0 or v_sub_amt <= sub_break_amt_upper)
                        else
                            sub_break_qty <= v_sub_qty
                            and (sub_break_qty_upper = 0 or v_sub_qty <= sub_break_qty_upper)
                    end
                )
              )
            order by break_qty desc, break_amt desc
        loop
            -- A bounded rule is spent once any level of it has been earned.
            -- OM10100 zeroes the basis rather than breaking the loop, which
            -- amounts to the same thing and is what this stops instead.
            exit when v_fired and v_seq.break_bound = 'bounded';

            -- A percentage that has already paid stops the ladder, unless the
            -- rule says the levels stack on the remainder.
            exit when v_fired and v_seq.reward = 'percent' and not v_seq.pct_by_level;

            v_p := 0;
            v_basis := 0;

            if v_seq.scope = 'line' then
                -- The money a percentage reward is measured against: every
                -- condition item this rule names, as the cart stands before this
                -- level consumes anything.
                select coalesce(sum(c.amount), 0) into v_basis
                from discount_condition_item ci
                join _cart c
                    on c.product_id = ci.product_id and c.uom_code = ci.uom_code
                where ci.sequence_id = v_seq.sequence_id;

                -- Every condition item is measured on its own, and each one that
                -- qualifies consumes what it used.
                for v_cond in
                    select ci.product_id, ci.uom_code
                    from discount_condition_item ci
                    where ci.sequence_id = v_seq.sequence_id
                loop
                    select
                        case when v_seq.break_by = 'qty' then c.qty else c.amount end
                    into v_measure
                    from _cart c
                    where c.product_id = v_cond.product_id
                      and c.uom_code = v_cond.uom_code;

                    if v_measure is null then
                        continue;
                    end if;

                    -- A bounded level is a band: below its floor it does not
                    -- apply, and above its ceiling it stops applying rather than
                    -- paying out forever. Zero ceiling means open-ended.
                    if v_seq.break_bound = 'bounded' then
                        if v_seq.break_by = 'qty' then
                            continue when v_break.break_qty_upper > 0
                                and v_measure > v_break.break_qty_upper;
                        else
                            continue when v_break.break_amt_upper > 0
                                and v_measure > v_break.break_amt_upper;
                        end if;
                    end if;

                    v_available := case
                        when v_seq.break_by = 'qty' and v_break.break_qty > 0
                            then floor(v_measure / v_break.break_qty)
                        when v_seq.break_by = 'amount' and v_break.break_amt > 0
                            then floor(v_measure / v_break.break_amt)
                        else 0
                    end;

                    if coalesce(v_available, 0) < 1 then
                        continue;
                    end if;

                    -- A bounded level is worth one lot however many times the
                    -- basket clears it — CalcDiscountRate's last two lines.
                    if v_seq.break_bound = 'bounded' then
                        v_available := 1;
                    end if;

                    -- RequiredType 'Q' caps the lots at the number of complete
                    -- sets, which is what maxGLot does over there.
                    if v_seq.required_type = 'qty_capped' and v_available > v_lots then
                        v_available := v_lots;
                    end if;

                    if v_break.max_lot > 0 and v_p + v_available > v_break.max_lot then
                        v_available := greatest(v_break.max_lot - v_p, 0);
                    end if;

                    if v_available < 1 then
                        continue;
                    end if;

                    v_p := v_p + v_available;

                    -- Consume, and record it so the shared pool can be reduced by
                    -- the delta. This is also what stops one basket earning the
                    -- same level twice through two levels of the same rule.
                    insert into _used (product_id, uom_code, qty, amount)
                    select
                        v_cond.product_id,
                        v_cond.uom_code,
                        case when v_seq.break_by = 'qty'
                             then v_available * v_break.break_qty else 0 end,
                        case when v_seq.break_by = 'qty'
                             then v_available * v_break.break_qty
                                  * (case when c.qty > 0 then c.amount / c.qty else 0 end)
                             else v_available * v_break.break_amt end
                    from _cart c
                    where c.product_id = v_cond.product_id
                      and c.uom_code = v_cond.uom_code;

                    if v_seq.break_by = 'qty' then
                        update _cart
                        set qty = qty - v_available * v_break.break_qty,
                            amount = amount
                                - v_available * v_break.break_qty
                                * (case when qty > 0 then amount / qty else 0 end)
                        where product_id = v_cond.product_id
                          and uom_code = v_cond.uom_code;
                    else
                        update _cart
                        set amount = amount - v_available * v_break.break_amt
                        where product_id = v_cond.product_id
                          and uom_code = v_cond.uom_code;
                    end if;

                    exit when v_seq.break_bound = 'bounded';
                end loop;

            elsif v_seq.scope = 'group' then
                v_measure := case
                    when v_seq.break_by = 'qty' then v_group_qty else v_group_amt
                end;

                v_p := case
                    when v_seq.break_by = 'qty' and v_break.break_qty > 0
                         and v_measure >= v_break.break_qty
                        then floor(v_measure / v_break.break_qty)
                    when v_seq.break_by = 'amount' and v_break.break_amt > 0
                         and v_measure >= v_break.break_amt
                        then floor(v_measure / v_break.break_amt)
                    else 0
                end;

                if v_seq.break_bound = 'bounded' then
                    if v_seq.break_by = 'qty' and v_break.break_qty_upper > 0
                       and v_measure > v_break.break_qty_upper then
                        v_p := 0;
                    elsif v_seq.break_by = 'amount' and v_break.break_amt_upper > 0
                          and v_measure > v_break.break_amt_upper then
                        v_p := 0;
                    elsif v_p > 1 then
                        v_p := 1;
                    end if;
                end if;

                if v_seq.required_type = 'qty_capped' and v_p > v_lots then
                    v_p := v_lots;
                end if;

                if v_break.max_lot > 0 and v_p > v_break.max_lot then
                    v_p := v_break.max_lot;
                end if;

                v_basis := v_group_amt;

                if v_p >= 1 then
                    if v_seq.break_by = 'qty' then
                        v_group_qty := v_group_qty - v_p * v_break.break_qty;
                    else
                        v_group_amt := v_group_amt - v_p * v_break.break_amt;
                    end if;
                end if;

            else
                -- Order scope. Money only, and it used to be pinned to one lot
                -- unconditionally; the flag says so now instead, which is why a
                -- 'lower' order rule can finally repeat.
                if v_break.break_amt > 0 and v_order_left >= v_break.break_amt then
                    v_p := floor(v_order_left / v_break.break_amt);

                    if v_seq.break_bound = 'bounded' then
                        if v_break.break_amt_upper > 0
                           and v_order_left > v_break.break_amt_upper then
                            v_p := 0;
                        elsif v_p > 1 then
                            v_p := 1;
                        end if;
                    end if;

                    if v_seq.required_type = 'qty_capped' and v_p > v_lots then
                        v_p := v_lots;
                    end if;

                    if v_break.max_lot > 0 and v_p > v_break.max_lot then
                        v_p := v_break.max_lot;
                    end if;

                    if v_p >= 1 then
                        v_basis := v_order_left;
                        v_order_left := v_order_left - v_p * v_break.break_amt;
                    end if;
                end if;
            end if;

            if v_p < 1 then
                continue;
            end if;

            v_fired := true;

            -- A percentage is a threshold, not a multiplier. `CalcPromo.cs`
            -- multiplies the portion count in twice and accumulates the
            -- percentage as well; neither is arithmetic a campaign was written
            -- to mean, and neither is reproduced.
            if v_seq.reward = 'percent' then
                v_p := 1;
                v_percent := v_break.disc_amt;
                v_amount := round(v_basis * v_break.disc_amt / 100);
            else
                v_percent := null;
                v_amount := case
                    when v_seq.reward in ('amount', 'amount_or_item')
                        then round(v_break.disc_amt * v_p)
                    else 0
                end;
            end if;

            -- The goods on offer, each one carrying what the depot can actually
            -- give. `available_qty` is in the gift's own unit, because that is
            -- the unit the rep is about to promise in.
            v_free := '[]'::jsonb;

            if v_seq.reward in ('free_item', 'amount_or_item') then
                select coalesce(jsonb_agg(item order by rn), '[]'::jsonb)
                into v_free
                from (
                    select
                        row_number() over (order by fi.sort_order, p.name) as rn,
                        jsonb_build_object(
                            'product_id', fi.product_id,
                            'product_code', p.code,
                            'product_name', p.name,
                            'uom_code', fi.uom_code,
                            -- The code is what submit_order matches a product_uom
                            -- on; the name is what the rep reads. Both travel,
                            -- because neither can stand in for the other.
                            'uom_name', fu.name,
                            'qty', fi.free_qty * v_p,
                            'available_qty',
                                floor(coalesce(st.qty_base, 0) / pu.conversion_rate),
                            'chosen', v_seq.auto_free_item
                                or row_number() over (order by fi.sort_order, p.name) = 1
                        ) as item
                    from discount_free_item fi
                    join product p on p.id = fi.product_id
                    join uom fu on fu.code = fi.uom_code
                    join product_uom pu
                        on pu.product_id = fi.product_id and pu.uom_code = fi.uom_code
                    left join _free_stock st on st.product_id = fi.product_id
                    where fi.sequence_id = v_seq.sequence_id
                ) ranked;

                -- Whatever this rule is about to promise comes out of the pool,
                -- so a second rule giving the same product sees what is left
                -- rather than the same shelf twice. Only the pre-selected ones:
                -- an alternative the rep has not taken is not committed.
                update _free_stock st
                set qty_base = greatest(st.qty_base - w.wanted_base, 0)
                from (
                    select
                        (f ->> 'product_id')::uuid as product_id,
                        sum((f ->> 'qty')::numeric * pu.conversion_rate) as wanted_base
                    from jsonb_array_elements(v_free) as f
                    join product_uom pu
                        on pu.product_id = (f ->> 'product_id')::uuid
                       and pu.uom_code = (f ->> 'uom_code')::text
                    where coalesce((f ->> 'chosen')::boolean, false)
                    group by (f ->> 'product_id')::uuid
                ) w
                where st.product_id = w.product_id;

                -- What the depot cannot cover, said out loud rather than folded
                -- into a smaller gift. The rep is the one making the promise.
                v_short := v_short || coalesce(
                    (
                        select jsonb_agg(jsonb_build_object(
                            'sequence_id', v_seq.sequence_id,
                            'program_name', v_seq.program_name,
                            'product_id', f ->> 'product_id',
                            'product_name', f ->> 'product_name',
                            'uom_name', f ->> 'uom_name',
                            'wanted_qty', (f ->> 'qty')::integer,
                            'available_qty', (f ->> 'available_qty')::integer
                        ))
                        from jsonb_array_elements(v_free) as f
                        where coalesce((f ->> 'chosen')::boolean, false)
                          and (f ->> 'available_qty')::numeric < (f ->> 'qty')::numeric
                    ),
                    '[]'::jsonb
                );
            end if;

            v_total := v_total + v_amount;

            v_earned := v_earned || jsonb_build_object(
                'sequence_id', v_seq.sequence_id,
                'program_code', v_seq.program_code,
                'program_name', v_seq.program_name,
                'sequence_code', v_seq.sequence_code,
                'sequence_name', v_seq.sequence_name,
                'scope', v_seq.scope,
                'break_by', v_seq.break_by,
                'reward', v_seq.reward,
                'break_id', v_break.id,
                'break_name', v_break.name,
                'break_qty', v_break.break_qty,
                'break_amt', v_break.break_amt,
                'portion', v_p,
                'discount_amount', v_amount::bigint,
                'percent', v_percent,
                -- A rule where the rep picks: goods versus money, or which goods.
                'needs_choice', v_seq.reward = 'amount_or_item'
                    or (v_seq.reward = 'free_item' and not v_seq.auto_free_item),
                'free_items', v_free
            );
        end loop;

        -- Every rule that fires takes its bite out of the pool, whether or not it
        -- was measured against it. The flag governs reading; the eating is
        -- unconditional, which is what makes a later flagged rule see less than
        -- the customer actually bought.
        if v_fired then
            update _cart_shared s
            set qty = greatest(s.qty - u.qty, 0),
                amount = greatest(s.amount - u.amount, 0)
            from (
                select product_id, uom_code, sum(qty) as qty, sum(amount) as amount
                from _used group by product_id, uom_code
            ) u
            where s.product_id = u.product_id and s.uom_code = u.uom_code;

            select v_vetoed || coalesce(array_agg(x.excludes_id), '{}')
            into v_vetoed
            from discount_exclusion x
            where x.sequence_id = v_seq.sequence_id;
        end if;
    end loop;

    return jsonb_build_object(
        'order_amount', v_order_amt::bigint,
        'total_discount', v_total::bigint,
        'earned', v_earned,
        -- Gifts the depot cannot cover. Not an error and not a block: the order
        -- books either way, and the depot settles it. What it buys is that the
        -- rep knows before they promise rather than after.
        'stock_shortfalls', v_short
    );
end;
$$;

revoke execute on function calculate_promotions(uuid, jsonb, date) from public;
grant execute on function calculate_promotions(uuid, jsonb, date) to authenticated;

comment on function calculate_promotions(uuid, jsonb, date) is
    'Line, group and order discounts over one cart, with each gift carrying what '
    'the depot can actually give. Honours OM10100 BreakBoundType, IsDeductQtyAmt, '
    'ExcludePromo, FirstOrder, RequiredType, SubBreakType and PctDiscountByLevel; '
    'budgets are not ported.';
