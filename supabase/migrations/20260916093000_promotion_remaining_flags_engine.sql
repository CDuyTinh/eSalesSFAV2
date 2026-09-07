-- =============================================================================
-- The engine reads the last of the flags
--
-- Split from 20260916090000 because a new enum value cannot be used in the
-- transaction that adds it, and `any_n` is used here.
-- =============================================================================

drop function if exists applicable_discount_sequences(uuid, date);

create function applicable_discount_sequences(
    p_customer_id uuid,
    p_order_date  date default current_date
)
returns table (
    sequence_id        uuid,
    program_id         uuid,
    program_code       text,
    program_name       text,
    sequence_code      text,
    sequence_name      text,
    scope              discount_scope,
    break_by           discount_break_by,
    reward             discount_reward,
    auto_free_item     boolean,
    exclude_other_disc boolean,
    priority           integer,
    break_bound        discount_break_bound,
    deducts_shared     boolean,
    required_type      discount_required_type,
    sub_break_type     discount_sub_break_type,
    pct_by_level       boolean,
    budget_id          uuid,
    required_number    integer,
    only_once          boolean,
    exact_qty          boolean,
    donate_group       boolean,
    convert_amount_to_goods boolean
)
language sql
stable
security invoker
set search_path = public, pg_temp
as $$
    with me as (
        select id, branch_id, class_id, channel_id, shop_type_id
        from customer where id = p_customer_id
    ),
    history as (
        select exists (
            select 1 from sales_order
            where customer_id = p_customer_id
              and status <> 'cancelled'
        ) as has_ordered
    ),
    live as (
        select s.*, p.scope, p.code as program_code, p.name as program_name
        from discount_sequence s
        join discount_program p on p.id = s.program_id
        cross join me
        cross join history h
        where s.is_active
          and p.is_active
          and s.from_date <= p_order_date
          and (s.open_ended or p_order_date <= s.to_date)
          and (s.branch_id is null or s.branch_id = me.branch_id)
          and (not s.first_order_only or not h.has_ordered)
    ),
    eligible as (
        select l.*
        from live l
        cross join me
        where not exists (select 1 from discount_audience a where a.sequence_id = l.id)
           or exists (
                select 1 from discount_audience a
                where a.sequence_id = l.id
                  and (
                      (a.kind = 'customer'  and a.target_id = me.id)
                   or (a.kind = 'class'     and a.target_id = me.class_id)
                   or (a.kind = 'channel'   and a.target_id = me.channel_id)
                   or (a.kind = 'shop_type' and a.target_id = me.shop_type_id)
                  )
           )
    )
    select
        e.id, e.program_id, e.program_code, e.program_name,
        e.code, e.name, e.scope, e.break_by, e.reward,
        e.auto_free_item, e.exclude_other_disc, e.priority,
        e.break_bound, e.deducts_shared,
        e.required_type, e.sub_break_type, e.pct_by_level,
        e.budget_id,
        e.required_number, e.only_once, e.exact_qty,
        e.donate_group, e.convert_amount_to_goods
    from eligible e
    where
        case
            when exists (select 1 from eligible x where x.exclude_other_disc)
                then e.exclude_other_disc
            else true
        end
    order by e.scope, e.priority desc, e.program_code, e.code;
$$;

comment on function applicable_discount_sequences(uuid, date) is
    'Discount rules this outlet qualifies for on this date. The audience half of '
    'the legacy API_GetDiscSeq, plus OM10100''s first-order test.';

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
    v_over       jsonb := '[]'::jsonb;   -- rules a budget could not pay for
    v_budget     record;
    v_want       numeric;                -- what this level would draw down
    v_pot        numeric;                -- what the allocation has left
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

    -- Discount already attributed to each line of the basket.
    --
    -- A percentage is taken on what the customer is still being charged, not on
    -- what they were charged before anyone discounted anything: OM10100 writes
    -- each discount onto the order lines and reads `LineAmt` back, so a rule
    -- that fires later sees the reduced figure. This ledger is that write.
    create temporary table if not exists _line_disc (
        product_id uuid,
        uom_code   text,
        amount     numeric
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
    delete from _line_disc where true;
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

        -- "Buy any N of these" — RequiredType 'R'. The values on the items are
        -- ignored; what counts is how many distinct products off the list the
        -- basket contains at all.
        if v_seq.required_type = 'any_n' then
            select count(*)
            into v_lots
            from discount_condition_item ci
            where ci.sequence_id = v_seq.sequence_id
              and exists (
                  select 1 from _cart c
                  where c.product_id = ci.product_id
                    and c.uom_code = ci.uom_code
                    and c.qty > 0
              );

            if v_lots < greatest(v_seq.required_number, 1) then
                continue;
            end if;

            -- Past the gate it is a gate and nothing more: it does not cap lots
            -- the way 'qty_capped' does.
            v_lots := 0;

        elsif v_seq.required_type <> 'none' then
            -- A required item the basket does not contain at all kills the rule
            -- outright. This is the half `min()` cannot express: an absent item
            -- contributes no row, so a minimum over what is present would let a
            -- rule requiring A and B fire on A alone.
            if exists (
                select 1
                from discount_condition_item ci
                where ci.sequence_id = v_seq.sequence_id
                  and ci.required_value > 0
                  and not exists (
                      select 1 from _cart c
                      where c.product_id = ci.product_id
                        and c.uom_code = ci.uom_code
                        and c.qty > 0
                  )
            ) then
                continue;
            end if;

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

            -- OnlyMaxLineRef: the rule pays once per order and then stops,
            -- whatever else the ladder would have offered.
            exit when v_fired and v_seq.only_once;

            -- A percentage that has already paid stops the ladder, unless the
            -- rule says the levels stack on the remainder.
            exit when v_fired and v_seq.reward = 'percent' and not v_seq.pct_by_level;

            v_p := 0;
            v_basis := 0;

            if v_seq.scope = 'line' then
                -- The money a percentage reward is measured against: every
                -- condition item this rule names, as the cart stands before this
                -- level consumes anything.
                select
                    coalesce(sum(c.amount), 0)
                    - coalesce(sum(d.amount), 0)
                into v_basis
                from discount_condition_item ci
                join _cart_base c
                    on c.product_id = ci.product_id and c.uom_code = ci.uom_code
                left join lateral (
                    select coalesce(sum(ld.amount), 0) as amount
                    from _line_disc ld
                    where ld.product_id = ci.product_id
                      and ld.uom_code = ci.uom_code
                ) d on true
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

                    -- ExactQty: the level has to be hit on the nose. A basket of
                    -- eleven against a level of ten is not a level of ten with
                    -- one left over, it is nothing — which is what
                    -- GetBundleDiscBreak's `BreakAmt == bundleNbr` says.
                    if v_seq.exact_qty then
                        continue when v_measure <> (
                            case when v_seq.break_by = 'qty'
                                 then v_break.break_qty else v_break.break_amt end
                        );
                    end if;

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

                -- ExactQty at group level: the pooled measure has to land on
                -- the level exactly.
                if v_seq.exact_qty and v_measure <> (
                    case when v_seq.break_by = 'qty'
                         then v_break.break_qty else v_break.break_amt end
                ) then
                    v_p := 0;
                end if;

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

                -- Not v_group_amt: that has had earlier levels of this same rule
                -- eaten out of it for threshold purposes, which is a different
                -- number from what the customer is being charged.
                select
                    coalesce(sum(c.amount), 0)
                    - coalesce(sum(d.amount), 0)
                into v_basis
                from discount_condition_item ci
                join _cart_base c
                    on c.product_id = ci.product_id and c.uom_code = ci.uom_code
                left join lateral (
                    select coalesce(sum(ld.amount), 0) as amount
                    from _line_disc ld
                    where ld.product_id = ci.product_id
                      and ld.uom_code = ci.uom_code
                ) d on true
                where ci.sequence_id = v_seq.sequence_id;

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
                        -- The whole order, less what has already come off it.
                        -- OM10100 reads docAmt here, not the remainder its
                        -- threshold walk is left holding.
                        select v_order_amt - coalesce(sum(amount), 0)
                        into v_basis from _line_disc;
                        v_order_left := v_order_left - v_p * v_break.break_amt;
                    end if;
                end if;
            end if;

            if v_p < 1 then
                continue;
            end if;

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
            end if;

            -- ---------------------------------------------------------------
            -- Can a budget pay for this?
            --
            -- A campaign can be live, qualified for, and still not payable: head
            -- office allocates a pot per route or per outlet, and when it runs
            -- out the promotion stops applying. `CheckAvailableDiscBudget` drops
            -- the rule and reports it separately, which is what the old client's
            -- `autoPromoOutOfBudget` list was carrying.
            --
            -- What is drawn down depends on what the pot counts: money for an
            -- amount budget, lots for a portion budget, units for a goods one.
            -- ---------------------------------------------------------------
            if v_seq.budget_id is not null then
                select b.*, a.allocated - a.spent as remaining
                into v_budget
                from discount_budget b
                left join discount_budget_allocation a
                    on a.budget_id = b.id
                   and a.target_id = case
                       when b.alloc_by = 'customer' then p_customer_id
                       else (
                           select r.id from sales_route r
                           where r.salesperson_id = current_salesperson_id()
                             and r.is_active
                           order by r.code
                           limit 1
                       )
                   end
                where b.id = v_seq.budget_id
                  and b.is_active
                  and p_order_date between b.from_date and b.to_date;

                -- An allocation that does not exist is zero, not unlimited —
                -- which is how the legacy reads a missing OM_PPAlloc too. A
                -- campaign pointed at a budget nobody funded is unfunded.
                v_pot := coalesce(v_budget.remaining, 0);

                v_want := case v_budget.counts
                    when 'lots' then v_p
                    when 'free_item' then coalesce(
                        (
                            select sum((f ->> 'qty')::numeric)
                            from jsonb_array_elements(v_free) as f
                            where coalesce((f ->> 'chosen')::boolean, false)
                        ),
                        0
                    )
                    else v_amount
                end;

                if v_budget.id is null or v_pot < v_want then
                    v_over := v_over || jsonb_build_object(
                        'sequence_id', v_seq.sequence_id,
                        'program_name', v_seq.program_name,
                        'sequence_name', v_seq.sequence_name,
                        'break_name', v_break.name,
                        'budget_name', coalesce(v_budget.name, ''),
                        'wanted', v_want,
                        'remaining', v_pot
                    );

                    -- Not applied, so nothing about it counts: not the money,
                    -- not the gift, and not the bite out of any pool.
                    continue;
                end if;
            end if;

            v_fired := true;

            -- ConvertDiscAmtToFreeItem: the rule is written as money and paid in
            -- goods. `maxLot = promoAmt / free.PromoPrice` over there — the
            -- discount buys units of the gift at a price the campaign sets, and
            -- what the depot cannot cover caps it. The money then stops being
            -- money: the customer is given the goods instead of the discount.
            if v_seq.convert_amount_to_goods and v_amount > 0 then
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
                            'uom_name', fu.name,
                            'qty', least(
                                floor(v_amount / nullif(fi.promo_price, 0)),
                                floor(coalesce(st.qty_base, 0) / pu.conversion_rate)
                            ),
                            'available_qty',
                                floor(coalesce(st.qty_base, 0) / pu.conversion_rate),
                            'chosen', true
                        ) as item
                    from discount_free_item fi
                    join product p on p.id = fi.product_id
                    join uom fu on fu.code = fi.uom_code
                    join product_uom pu
                        on pu.product_id = fi.product_id and pu.uom_code = fi.uom_code
                    left join _free_stock st on st.product_id = fi.product_id
                    where fi.sequence_id = v_seq.sequence_id
                      and fi.promo_price > 0
                ) ranked;

                -- Nothing to convert into: the campaign is misconfigured, and
                -- paying the money instead would be inventing a reward nobody
                -- authorised.
                if jsonb_array_length(v_free) = 0
                   or coalesce((v_free -> 0 ->> 'qty')::numeric, 0) < 1 then
                    v_free := '[]'::jsonb;
                    continue;
                end if;

                v_amount := 0;
            end if;

            -- DonateGroupProduct: the gift list stops being "one of each" and
            -- becomes a pool of that many units shared evenly between them.
            -- OM10100 does exactly this — sums the quantities and divides by the
            -- number of items — so a rule offering 2 A and 1 B gives 1.5 of each
            -- rather than 2 and 1. Rounded down here, because half a case is not
            -- a thing a van can carry.
            if v_seq.donate_group and jsonb_array_length(v_free) > 0 then
                select coalesce(jsonb_agg(
                    item || jsonb_build_object('qty', floor(pool / cnt))
                ), '[]'::jsonb)
                into v_free
                from (
                    select
                        f as item,
                        sum((f ->> 'qty')::numeric) over () as pool,
                        count(*) over () as cnt
                    from jsonb_array_elements(v_free) as f
                ) spread;
            end if;

            -- Now that a budget has paid for it, what this rule promises comes
            -- out of the depot's pool: a second rule giving the same product
            -- sees what is left rather than the same shelf twice. Only the
            -- pre-selected ones — an alternative the rep has not taken is not
            -- committed to anybody.
            if v_seq.reward in ('free_item', 'amount_or_item') then
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


            -- Write the discount onto the lines it came off, so the next rule
            -- measuring a percentage sees what the customer is still being
            -- charged. Split in proportion to what each line contributes, which
            -- is what OM10100's UpdateLineDiscAmt and UpdateGroupDiscByInvtIDV2
            -- do one line at a time.
            if v_amount > 0 then
                insert into _line_disc (product_id, uom_code, amount)
                select
                    c.product_id,
                    c.uom_code,
                    v_amount * c.amount / nullif(sum(c.amount) over (), 0)
                from _cart_base c
                where
                    case
                        when v_seq.scope = 'order' then true
                        else exists (
                            select 1 from discount_condition_item ci
                            where ci.sequence_id = v_seq.sequence_id
                              and ci.product_id = c.product_id
                              and ci.uom_code = c.uom_code
                        )
                    end;
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
        'stock_shortfalls', v_short,
        -- Rules the basket qualified for and a budget could not pay for.
        -- Reported rather than hidden: a rep who cannot see why a promotion
        -- stopped applying concludes the app is wrong.
        'out_of_budget', v_over
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
