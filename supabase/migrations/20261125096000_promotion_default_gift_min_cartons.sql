-- =============================================================================
-- Quà mặc định và đơn tối thiểu theo thùng (OM10100, 2026-10)
--
-- THE DEFAULT GIFT. An "or" gift (the rep picks one of several) used to
-- default to the campaign's first. The BA ruled on 2026-10-13, for every rule:
-- a gift the depot can cover for the lots granted; among those, one the order
-- itself sells; then the one with the most stock, counted in cases so that a
-- thousand packs and a thousand cartons are not equal; then the campaign's own
-- order. The rep can still pick another.
--
-- THE MINIMUM ORDER (BUG-2026-74458). A sales order is booked only when what
-- it sells, counted in cases, reaches min_order_carton_qty (0.5 unless set).
-- Gifts do not count. A product with no case unit counts its base unit as the
-- case, as the BA ruled. Enforced by submit_order; the app checks first so the
-- rep hears it before the confirmation page.
-- =============================================================================

insert into app_setting (key, value, description)
values ('min_order_carton_qty', '0.5', 'Đơn hàng phải đạt tối thiểu số thùng quy đổi này (không tính hàng KM)')
on conflict (key) do nothing;

CREATE OR REPLACE FUNCTION public.calculate_promotions(p_customer_id uuid, p_lines jsonb, p_order_date date DEFAULT CURRENT_DATE, p_seller uuid DEFAULT NULL::uuid, p_proposal_picks uuid[] DEFAULT NULL::uuid[])
 RETURNS jsonb
 LANGUAGE plpgsql
 SET search_path TO 'public', 'pg_temp'
AS $function$
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
    v_bundles    numeric;   -- complete bundles a combo rule can make
    v_exact_ok   boolean;   -- every bundle item divides evenly
    v_ratios     integer;   -- how many distinct bundle counts the items give
    v_items      integer;
    v_site       uuid;      -- the warehouse the gifts would come out of
    v_short      jsonb := '[]'::jsonb;
    v_over       jsonb := '[]'::jsonb;   -- rules a budget could not pay for
    v_budget     record;
    v_want       numeric;                -- what this level would draw down
    v_pot        numeric;                -- what the allocation has left
    v_cap        integer;   -- lots this level may still pay out; null = no limit
    v_cap_quota  integer;   -- what the outlet has left of the level's lifetime lots
    v_cap_budget integer;   -- what the budget can still pay, in lots of this level
    v_cap_stock  integer;   -- what the depot can still give, in lots of this level
    v_cap_pot    numeric;
    v_cap_cost   numeric;   -- what one lot of this level draws from its budget
    v_proposal   uuid;      -- the "CTKM lựa chọn" group the rule belongs to
    v_req        integer;   -- lots the basket earned before the cap
    v_cut        jsonb := '[]'::jsonb;   -- levels the cap cut (PromoShortages)
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
    -- What this calculation has already drawn from each budget, so two rules
    -- sharing a pot do not both spend the same remainder.
    create temporary table if not exists _budget_used (
        budget_id uuid,
        used      numeric
    );
    delete from _budget_used where true;

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
        -- CTKM lựa chọn: a group whose pick has been made runs that one rule
        -- only, so the one taken gets the basket the others would have shared
        -- (OM10100's IsProposalNotPicked).
        select s.proposal_id into v_proposal
        from discount_sequence s where s.id = v_seq.sequence_id;

        if v_proposal is not null
           and not v_seq.sequence_id = any (coalesce(p_proposal_picks, '{}'))
           and exists (
               select 1 from discount_sequence ps
               where ps.id = any (coalesce(p_proposal_picks, '{}'))
                 and ps.proposal_id = v_proposal
           ) then
            continue;
        end if;

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

        -- ---------------------------------------------------------------
        -- Combo: how many complete bundles the basket can make
        --
        -- DiscClass BB, B1 and CB. Each condition item names how many of it
        -- go into one bundle, every item divides the basket by its own
        -- figure, and the number of bundles is the smallest of those — a set
        -- is only complete when every part of it is there. An item the basket
        -- does not contain at all makes zero bundles and ends the rule, which
        -- is `GetTotQtyAmtBundle` returning false.
        --
        -- The ladder then runs on bundles rather than on units or money:
        -- `Array.Sort(bundleNbr); qtyAmt = bundleNbr[0]`, and every level is
        -- read as a number of bundles.
        -- ---------------------------------------------------------------
        if v_seq.is_bundle then
            select
                -- coalesce inside the min, not outside it. An item the basket
                -- does not contain joins as null, and min() ignores nulls — so
                -- without this a bundle of A and B would count itself complete
                -- on a basket of A alone. The same shape of mistake as the
                -- required-items check, caught here by the tests rather than by
                -- writing the next feature.
                floor(min(coalesce(
                    case when v_seq.break_by = 'amount'
                         then c.amount / nullif(ci.bundle_amt, 0)
                         else c.qty / nullif(ci.bundle_qty, 0)
                    end,
                    0
                ))),
                -- ExactQty over a bundle asks two things at once: every item
                -- divides evenly, and they all divide to the same number. A
                -- basket of three A and five B against a bundle of one each is
                -- three bundles and two spare B, which an exact rule refuses.
                bool_and(
                    case when v_seq.break_by = 'amount'
                         then c.amount % nullif(ci.bundle_amt, 0) = 0
                         else c.qty % nullif(ci.bundle_qty, 0) = 0
                    end
                ),
                count(distinct
                    case when v_seq.break_by = 'amount'
                         then c.amount / nullif(ci.bundle_amt, 0)
                         else c.qty / nullif(ci.bundle_qty, 0)
                    end
                ),
                count(*)
            into v_bundles, v_exact_ok, v_ratios, v_items
            from discount_condition_item ci
            left join _cart c
                on c.product_id = ci.product_id and c.uom_code = ci.uom_code
            where ci.sequence_id = v_seq.sequence_id;

            -- A missing item leaves a null in the minimum, and a bundle nobody
            -- can complete is not a smaller bundle.
            if coalesce(v_bundles, 0) < 1 then
                continue;
            end if;

            if v_seq.exact_qty and (not coalesce(v_exact_ok, false) or v_ratios > 1) then
                continue;
            end if;
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

            -- ---------------------------------------------------------------
            -- How many lots this level may still pay (OM10100LevelCap)
            --
            -- ActualLot = min(lots earned, the outlet's remaining lots at this
            -- level, what the budget can pay, what the depot can give). A level
            -- that is cut pays what it can; the basket it did not use is left
            -- for the lower levels, which is what keeps a customer who outgrew
            -- a capped level from getting nothing at all. Percentages are not
            -- counted in lots: an amount budget pays them down instead, below.
            -- ---------------------------------------------------------------
            v_cap := null;
            v_cap_quota := null;
            v_cap_budget := null;
            v_cap_stock := null;
            v_cap_cost := null;
            v_cap_pot := null;
            v_req := 0;

            if v_seq.reward <> 'percent' then
                -- The outlet's lifetime lots at this level, less what its orders
                -- already hold. A cancelled order gives its lots back.
                if v_break.customer_max_lot is not null then
                    select greatest(v_break.customer_max_lot - coalesce(sum(d.portion), 0), 0)::integer
                    into v_cap_quota
                    from sales_order_discount d
                    join sales_order o on o.id = d.order_id
                    where d.break_id = v_break.id
                      and o.customer_id = p_customer_id
                      and o.status <> 'cancelled';
                end if;

                if v_seq.budget_id is not null then
                    v_cap_pot := coalesce(discount_budget_remaining(
                            v_seq.budget_id, p_customer_id, p_seller, p_order_date), 0)
                        - coalesce((select sum(used) from _budget_used
                                    where budget_id = v_seq.budget_id), 0);

                    -- One lot costs one in a lots budget, the level's money in an
                    -- amount budget, and its gifts in a goods budget.
                    v_cap_cost := (
                        select case b.counts
                            when 'lots' then 1
                            when 'free_item' then (
                                select case when v_seq.auto_free_item then sum(fi.free_qty)
                                            else max(fi.free_qty) end
                                from discount_free_item fi
                                where fi.sequence_id = v_seq.sequence_id
                            )
                            else v_break.disc_amt
                        end
                        from discount_budget b
                        where b.id = v_seq.budget_id
                    );

                    if v_cap_cost > 0 then
                        v_cap_budget := floor(greatest(v_cap_pot, 0) / v_cap_cost)::integer;
                    end if;
                end if;

                -- Gifts: every one of them must be there for an "and" gift, any
                -- one for an "or" gift. A pooled or converted gift divides its
                -- own way and is not counted in lots.
                if v_seq.reward = 'free_item'
                   and not v_seq.donate_group
                   and not v_seq.convert_amount_to_goods then
                    select (case when v_seq.auto_free_item then min(g.lots) else max(g.lots) end)::integer
                    into v_cap_stock
                    from (
                        select floor(coalesce(st.qty_base, 0)
                                     / nullif(fi.free_qty * pu.conversion_rate, 0)) as lots
                        from discount_free_item fi
                        join product_uom pu
                            on pu.product_id = fi.product_id and pu.uom_code = fi.uom_code
                        left join _free_stock st on st.product_id = fi.product_id
                        where fi.sequence_id = v_seq.sequence_id
                          and fi.free_qty > 0
                    ) g;
                end if;

                -- least() skips nulls: no limit from a source is no limit.
                v_cap := least(v_cap_quota, v_cap_budget, v_cap_stock);
            end if;

            v_p := 0;
            v_basis := 0;

            -- A combo is measured in bundles whoever declared its scope. The
            -- legacy ties combos to DiscType G and would misread one filed as a
            -- line rule; the flag decides here instead of the filing.
            if v_seq.scope = 'line' and not v_seq.is_bundle then
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

                    v_req := v_req + v_available;
                    if v_cap is not null and v_p + v_available > v_cap then
                        v_available := greatest(v_cap - v_p, 0);
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

            elsif v_seq.scope = 'group' or v_seq.is_bundle then
                -- A combo rule measures in bundles; every level of it is a
                -- number of bundles, whatever the rule counts its items in.
                v_measure := case
                    when v_seq.is_bundle then v_bundles
                    when v_seq.break_by = 'qty' then v_group_qty
                    else v_group_amt
                end;

                v_p := case
                    when (v_seq.is_bundle or v_seq.break_by = 'qty')
                         and v_break.break_qty > 0
                         and v_measure >= v_break.break_qty
                        then floor(v_measure / v_break.break_qty)
                    when not v_seq.is_bundle and v_seq.break_by = 'amount'
                         and v_break.break_amt > 0
                         and v_measure >= v_break.break_amt
                        then floor(v_measure / v_break.break_amt)
                    else 0
                end;

                -- ExactQty at group level: the pooled measure has to land on
                -- the level exactly. For a combo the evenness of the bundles
                -- was already checked; this is the level on top of it.
                if v_seq.exact_qty and v_measure <> (
                    case when v_seq.is_bundle or v_seq.break_by = 'qty'
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

                v_req := v_p;
                if v_cap is not null and v_p > v_cap then
                    v_p := v_cap;
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
                    if v_seq.is_bundle then
                        v_bundles := v_bundles - v_p * v_break.break_qty;
                    elsif v_seq.break_by = 'qty' then
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

                    v_req := v_p;
                    if v_cap is not null and v_p > v_cap then
                        v_p := v_cap;
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

            -- A level the cap cut, said out loud: what the basket earned, what
            -- is paid, and which limit did it. A level its budget cut to nothing
            -- is also the old "out of budget" entry the order screen explains.
            if v_cap is not null and v_req > v_p then
                v_cut := v_cut || jsonb_build_object(
                    'sequence_id', v_seq.sequence_id,
                    'program_name', v_seq.program_name,
                    'sequence_name', v_seq.sequence_name,
                    'break_id', v_break.id,
                    'break_name', v_break.name,
                    'requested_lots', v_req,
                    'granted_lots', v_p,
                    'out_of_quota', coalesce(v_cap_quota < v_req, false),
                    'quota_remaining', v_cap_quota,
                    'out_of_budget', coalesce(v_cap_budget < v_req, false),
                    'budget_lots', v_cap_budget,
                    'out_of_stock', coalesce(v_cap_stock < v_req, false),
                    'stock_lots', v_cap_stock
                );

                if v_p < 1 and coalesce(v_cap_budget < v_req, false) then
                    v_over := v_over || jsonb_build_object(
                        'sequence_id', v_seq.sequence_id,
                        'program_name', v_seq.program_name,
                        'sequence_name', v_seq.sequence_name,
                        'break_name', v_break.name,
                        'budget_name', coalesce((select name from discount_budget
                                                 where id = v_seq.budget_id), ''),
                        -- In the budget's own unit, as before: money, lots or goods.
                        'wanted', round(v_req * v_cap_cost),
                        'remaining', greatest(v_cap_pot, 0)
                    );
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
                            -- An "or" gift's default (BA 2026-10-13, every rule): a
                            -- gift the depot can cover first; among those, one the
                            -- order itself sells; then the most stock, counted in
                            -- cases so a pack and a carton compare fairly; then the
                            -- campaign's own order.
                            'chosen', v_seq.auto_free_item
                                or row_number() over (
                                    order by
                                        (coalesce(st.qty_base, 0) >= fi.free_qty * v_p * pu.conversion_rate) desc,
                                        exists (
                                            select 1
                                            from jsonb_array_elements(coalesce(p_lines, '[]'::jsonb)) as l
                                            where (l ->> 'product_id')::uuid = fi.product_id
                                        ) desc,
                                        coalesce(st.qty_base, 0) / coalesce(nullif((
                                            select pc.conversion_rate from product_uom pc
                                            where pc.product_id = fi.product_id and pc.uom_code = 'CASE'
                                        ), 0), 1) desc,
                                        fi.sort_order, p.name
                                ) = 1
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
                           where r.salesperson_id = coalesce(p_seller, current_salesperson_id())
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
                v_pot := coalesce(v_budget.remaining, 0)
                    - coalesce((select sum(used) from _budget_used
                                where budget_id = v_seq.budget_id), 0);

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

                -- A percentage is not counted in lots, so the cap above leaves
                -- it alone; an amount budget pays it down to what is left
                -- instead of dropping it (OM10100LevelCap, percent branch).
                if v_seq.reward = 'percent' and v_budget.counts = 'amount'
                   and v_pot >= 1 and v_pot < v_want then
                    v_cut := v_cut || jsonb_build_object(
                        'sequence_id', v_seq.sequence_id,
                        'program_name', v_seq.program_name,
                        'sequence_name', v_seq.sequence_name,
                        'break_id', v_break.id,
                        'break_name', v_break.name,
                        'requested_amount', v_want,
                        'granted_amount', floor(v_pot),
                        'out_of_budget', true
                    );
                    v_amount := floor(v_pot);
                    v_want := v_amount;
                end if;

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

            if v_seq.budget_id is not null then
                insert into _budget_used (budget_id, used) values (v_seq.budget_id, v_want);
            end if;

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

    -- -------------------------------------------------------------------------
    -- CTKM lựa chọn (OM10100ProposalGroup)
    --
    -- Rules sharing a proposal are alternatives: an order takes one of each
    -- group. The engine works out every member as above; this marks the one
    -- taken. The rep's pick when there is one; otherwise the best by kind first
    -- — percentage, then money, then goods — then the larger benefit of that
    -- kind (the higher percentage, the more money, the more gift units counted
    -- in the warehouse unit), then whichever came first. A money-or-goods rule
    -- counts as money when it pays money. Only what is taken is in the total.
    -- -------------------------------------------------------------------------
    if exists (
        select 1
        from jsonb_array_elements(v_earned) x
        join discount_sequence s on s.id = (x ->> 'sequence_id')::uuid
        where s.proposal_id is not null
    ) then
        with e as (
            select t.ord, t.x, s.proposal_id
            from jsonb_array_elements(v_earned) with ordinality as t(x, ord)
            left join discount_sequence s on s.id = (t.x ->> 'sequence_id')::uuid
        ),
        cand as (
            select
                e.proposal_id,
                (e.x ->> 'sequence_id')::uuid as seq,
                min(e.ord) as first_ord,
                case
                    when bool_or(e.x ->> 'reward' = 'percent') then 1
                    when sum((e.x ->> 'discount_amount')::numeric) > 0 then 2
                    else 3
                end as kind,
                case
                    when bool_or(e.x ->> 'reward' = 'percent')
                        then max((e.x ->> 'percent')::numeric)
                    when sum((e.x ->> 'discount_amount')::numeric) > 0
                        then sum((e.x ->> 'discount_amount')::numeric)
                    else sum(coalesce((
                        select sum((f ->> 'qty')::numeric * pu.conversion_rate)
                        from jsonb_array_elements(e.x -> 'free_items') f
                        join product_uom pu
                            on pu.product_id = (f ->> 'product_id')::uuid
                           and pu.uom_code = f ->> 'uom_code'
                        where coalesce((f ->> 'chosen')::boolean, false)
                    ), 0))
                end as value
            from e
            where e.proposal_id is not null
            group by e.proposal_id, (e.x ->> 'sequence_id')::uuid
        ),
        pick as (
            select distinct on (c.proposal_id) c.proposal_id, c.seq
            from cand c
            order by c.proposal_id,
                     (c.seq = any (coalesce(p_proposal_picks, '{}'))) desc,
                     c.kind, c.value desc, c.first_ord
        )
        select coalesce(jsonb_agg(
                   e.x || jsonb_build_object(
                       'proposal_id', e.proposal_id,
                       'proposal_name', p.name,
                       'proposal_descr', p.descr,
                       'is_default', e.proposal_id is null or exists (
                           select 1 from pick
                           where pick.proposal_id = e.proposal_id
                             and pick.seq = (e.x ->> 'sequence_id')::uuid
                       )
                   )
                   order by e.ord),
               '[]'::jsonb)
        into v_earned
        from e
        left join discount_proposal p on p.id = e.proposal_id;

        select coalesce(sum((x ->> 'discount_amount')::numeric), 0)
        into v_total
        from jsonb_array_elements(v_earned) x
        where coalesce((x ->> 'is_default')::boolean, true);
    end if;

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
        'out_of_budget', v_over,
        -- Levels paid less than they earned, and why (OM10100's PromoShortages).
        'level_caps', v_cut
    );
end;
$function$
;

CREATE OR REPLACE FUNCTION public.submit_order_core(p_order jsonb, p_seller uuid)
 RETURNS uuid
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public', 'pg_temp'
AS $function$
declare
    v_order_id   uuid := (p_order ->> 'id')::uuid;
    v_visit_id   uuid := (p_order ->> 'visit_id')::uuid;
    v_order_date date := coalesce((p_order ->> 'order_date')::date, current_date);
    -- Who the order is for (its owner, order number, route budget) and who is
    -- booking it. The same person on the rep's app; on a supervisor's
    -- work-with visit the rep they are riding with.
    v_sp_id      uuid := p_seller;
    v_caller     uuid := current_salesperson_id();
    v_sp_code    text;
    v_branch_id  uuid;
    v_customer   uuid;
    v_class_id   uuid;
    v_order_no   text;
    v_expected   integer;
    v_inserted   integer;
    v_promo      jsonb;
    v_cartons    numeric;   -- what the order sells, counted in cases
    v_min_cases  numeric;
    v_earned     jsonb;
    v_choice     jsonb;
    v_disc_id    uuid;
    v_take_amt   boolean;
    v_next_line  integer;
    v_free       jsonb;
    v_manual     jsonb;
    v_entry      record;
    v_amount     bigint;
    v_gross      bigint;
    v_budget     record;
    v_draw       numeric;
    v_ship_address uuid := nullif(p_order ->> 'ship_address_id', '')::uuid;
begin
    if v_order_id is null or v_visit_id is null then
        raise exception 'submit_order needs both id and visit_id';
    end if;

    select code, branch_id into v_sp_code, v_branch_id
    from salesperson where id = v_sp_id;

    -- The customer comes from the visit rather than the payload: an order must
    -- belong to the outlet the rep actually called on. This also fails closed
    -- on someone else's visit, since the rep cannot read it.
    select v.customer_id, c.class_id into v_customer, v_class_id
    from visit v
    join customer c on c.id = v.customer_id
    where v.id = v_visit_id and v.salesperson_id = v_caller
      and (v_caller = v_sp_id or v.work_with_salesperson_id = v_sp_id);

    if v_customer is null then
        raise exception 'visit % is not an open visit of this salesperson', v_visit_id;
    end if;

    -- An address that belongs to a different outlet would send the goods to
    -- the wrong shop, and RLS would not stop it: the rep can read every
    -- customer's addresses, so the tie to this order's customer is checked here.
    if v_ship_address is not null and not exists (
        select 1 from customer_ship_address a
        where a.id = v_ship_address and a.customer_id = v_customer and a.is_active
    ) then
        raise exception 'địa chỉ giao hàng không thuộc khách hàng này';
    end if;

    -- Derived from the order id, so a retry produces the same number instead of
    -- burning a sequence value per attempt.
    v_order_no := v_sp_code || '-' || to_char(v_order_date, 'YYYYMMDD') || '-' ||
                  upper(substr(replace(v_order_id::text, '-', ''), 1, 6));

    insert into sales_order (
        id, order_no, customer_id, salesperson_id, branch_id, visit_id,
        order_date, note, client_total_amount, client_created_at, ship_address_id
    )
    values (
        v_order_id, v_order_no, v_customer, v_sp_id, v_branch_id, v_visit_id,
        v_order_date,
        nullif(p_order ->> 'note', ''),
        coalesce((p_order ->> 'client_total_amount')::bigint, 0),
        coalesce((p_order ->> 'client_created_at')::timestamptz, now()),
        v_ship_address
    )
    on conflict (id) do nothing;

    if not found then
        -- Replayed from the outbox; the first delivery already booked it.
        return v_order_id;
    end if;

    if v_caller is distinct from v_sp_id then
        update sales_order set taken_by_salesperson_id = v_caller where id = v_order_id;
    end if;

    select count(*) into v_expected
    from jsonb_array_elements(p_order -> 'lines');

    if v_expected = 0 then
        raise exception 'order % has no lines', v_order_id;
    end if;

    with input as (
        select
            (l ->> 'line_no')::integer   as line_no,
            (l ->> 'product_id')::uuid   as product_id,
            (l ->> 'uom_code')::text     as uom_code,
            (l ->> 'qty')::integer       as qty
        from jsonb_array_elements(p_order -> 'lines') as l
    ),
    priced as (
        select
            i.line_no,
            i.product_id,
            i.uom_code,
            i.qty,
            pu.conversion_rate,
            pr.price,
            p.vat_basis_points,
            i.qty::bigint * pr.price as gross_amount
        from input i
        join product p on p.id = i.product_id and p.is_active
        join product_uom pu
            on pu.product_id = i.product_id and pu.uom_code = i.uom_code
        -- Prefer the customer's class over the list price. `nulls last` is what
        -- makes the fallback work, so it is load-bearing, not cosmetic.
        join lateral (
            select pl.price
            from price_list pl
            where pl.product_id = i.product_id
              and pl.uom_code = i.uom_code
              and (pl.class_id = v_class_id or pl.class_id is null)
              and v_order_date between pl.from_date and pl.to_date
            order by pl.class_id nulls last
            limit 1
        ) pr on true
    )
    insert into sales_order_line (
        order_id, line_no, product_id, uom_code, qty,
        conversion_rate, base_qty, price, vat_basis_points,
        gross_amount, vat_amount, line_amount
    )
    select
        v_order_id,
        line_no,
        product_id,
        uom_code,
        qty,
        conversion_rate,
        qty * conversion_rate,
        price,
        vat_basis_points,
        gross_amount,
        -- Half-up in integer arithmetic: + 5000 before dividing by 10000 is the
        -- rounding the client performs too, and both must agree to the dong.
        (gross_amount * vat_basis_points + 5000) / 10000,
        gross_amount + (gross_amount * vat_basis_points + 5000) / 10000
    from priced;

    get diagnostics v_inserted = row_count;

    -- An unpriced or discontinued product drops out of the joins above. Silently
    -- booking the rest would deliver an order the customer never agreed to.
    if v_inserted <> v_expected then
        raise exception
            'order %: % of % lines could not be priced for %',
            v_order_id, v_expected - v_inserted, v_expected, v_order_date;
    end if;

    -- -------------------------------------------------------------------------
    -- Đơn tối thiểu theo thùng (OM10100, BUG-2026-74458): what the order sells,
    -- counted in cases, must reach min_order_carton_qty. Gifts are not sales. A
    -- product with no case unit counts its base unit as the case, as the BA
    -- ruled.
    -- -------------------------------------------------------------------------
    select coalesce((
        select nullif(trim(value), '')::numeric
        from app_setting where key = 'min_order_carton_qty'
    ), 0.5)
    into v_min_cases;

    select coalesce(sum(
        l.qty * pu.conversion_rate::numeric / coalesce(nullif(pc.conversion_rate, 0), 1)
    ), 0)
    into v_cartons
    from sales_order_line l
    join product_uom pu on pu.product_id = l.product_id and pu.uom_code = l.uom_code
    left join product_uom pc on pc.product_id = l.product_id and pc.uom_code = 'CASE'
    where l.order_id = v_order_id and not l.is_free;

    if v_cartons < v_min_cases - 0.000000001 then
        raise exception 'đơn hàng phải đạt tối thiểu % thùng (hiện có % thùng)',
            trim(to_char(v_min_cases, 'FM999990.00')), trim(to_char(v_cartons, 'FM999990.00'));
    end if;

    -- -------------------------------------------------------------------------
    -- Automatic promotions, recomputed here from the lines just booked.
    -- -------------------------------------------------------------------------

    v_promo := calculate_promotions(
        v_customer,
        (select jsonb_agg(jsonb_build_object(
                    'product_id', product_id,
                    'uom_code', uom_code,
                    'qty', qty))
         from sales_order_line
         where order_id = v_order_id and not is_free),
        v_order_date,
        v_sp_id,
        -- CTKM lựa chọn: the rule the rep took in each group, if they chose.
        (select array_agg(value::uuid)
         from jsonb_array_elements_text(coalesce(p_order -> 'proposal_picks', '[]'::jsonb)))
    );

    select coalesce(max(line_no), 0) into v_next_line
    from sales_order_line where order_id = v_order_id;

    for v_earned in select * from jsonb_array_elements(v_promo -> 'earned')
    loop
        -- The alternatives of a "CTKM lựa chọn" group that were not taken.
        continue when not coalesce((v_earned ->> 'is_default')::boolean, true);

        -- What the rep chose for this rule, if they were offered anything.
        select e into v_choice
        from jsonb_array_elements(coalesce(p_order -> 'promotions', '[]'::jsonb)) as e
        where e ->> 'sequence_id' = v_earned ->> 'sequence_id'
        limit 1;

        -- `amount_or_item` defaults to the money, which is the branch that needs
        -- no further decision: taking goods means naming which goods.
        v_take_amt := case
            when v_earned ->> 'reward' <> 'amount_or_item' then null
            when v_choice is null then true
            else coalesce((v_choice ->> 'take_amount')::boolean, true)
        end;

        insert into sales_order_discount (
            order_id, sequence_id, break_id, portion,
            discount_amount, percent, descr
        )
        values (
            v_order_id,
            (v_earned ->> 'sequence_id')::uuid,
            (v_earned ->> 'break_id')::uuid,
            (v_earned ->> 'portion')::integer,
            case
                when v_earned ->> 'reward' = 'amount_or_item' and v_take_amt is false
                    then 0
                else (v_earned ->> 'discount_amount')::bigint
            end,
            (v_earned ->> 'percent')::numeric,
            coalesce(v_earned ->> 'sequence_name', '') ||
                case when coalesce(v_earned ->> 'break_name', '') <> ''
                     then ' - ' || (v_earned ->> 'break_name') else '' end
        )
        returning id into v_disc_id;

        -- Goods, when the rule gives them and the rep did not take the money.
        if v_earned ->> 'reward' = 'free_item'
           or (v_earned ->> 'reward' = 'amount_or_item' and v_take_amt is false) then

            for v_free in
                select * from jsonb_array_elements(v_earned -> 'free_items')
            loop
                -- A bundle gives everything on the list. A list of alternatives
                -- gives the one the rep named, or the pre-selected first.
                if v_choice ->> 'free_product_id' is not null then
                    if v_free ->> 'product_id' <> (v_choice ->> 'free_product_id')
                       or (v_choice ->> 'free_uom_code' is not null
                           and v_free ->> 'uom_code' <> (v_choice ->> 'free_uom_code')) then
                        continue;
                    end if;
                elsif not coalesce((v_free ->> 'chosen')::boolean, false) then
                    continue;
                end if;

                insert into sales_order_discount_free_item (
                    order_discount_id, product_id, uom_code, qty
                )
                values (
                    v_disc_id,
                    (v_free ->> 'product_id')::uuid,
                    (v_free ->> 'uom_code')::text,
                    (v_free ->> 'qty')::integer
                )
                on conflict (order_discount_id, product_id, uom_code) do update
                    set qty = sales_order_discount_free_item.qty + excluded.qty;

                v_next_line := v_next_line + 1;

                insert into sales_order_line (
                    order_id, line_no, product_id, uom_code, qty,
                    conversion_rate, base_qty, price, vat_basis_points,
                    gross_amount, discount_amount, vat_amount, line_amount, is_free
                )
                select
                    v_order_id,
                    v_next_line,
                    (v_free ->> 'product_id')::uuid,
                    (v_free ->> 'uom_code')::text,
                    (v_free ->> 'qty')::integer,
                    pu.conversion_rate,
                    (v_free ->> 'qty')::integer * pu.conversion_rate,
                    0, 0, 0, 0, 0, 0, true
                from product_uom pu
                where pu.product_id = (v_free ->> 'product_id')::uuid
                  and pu.uom_code = (v_free ->> 'uom_code')::text
                on conflict (order_id, product_id, uom_code, is_free) do update
                    set qty = sales_order_line.qty + excluded.qty,
                        base_qty = sales_order_line.base_qty + excluded.base_qty;
            end loop;
        end if;

        -- What this rule drew from its budget, if it has one. Recorded and
        -- deducted in the same breath: the engine has already refused anything
        -- the pot could not cover, so what is left here is the spending itself.
        --
        -- Booked per order rather than per level, because the pot is a limit on
        -- what a route or an outlet may be given and not on how the giving was
        -- arranged into levels.
        for v_budget in
            select
                s.budget_id,
                b.counts,
                a.id as allocation_id
            from discount_sequence s
            join discount_budget b on b.id = s.budget_id
            join discount_budget_allocation a
                on a.budget_id = b.id
               and a.target_id = case
                   when b.alloc_by = 'customer' then v_customer
                   else (
                       select r.id from sales_route r
                       where r.salesperson_id = v_sp_id and r.is_active
                       order by r.code limit 1
                   )
               end
            where s.id = (v_earned ->> 'sequence_id')::uuid
              and s.budget_id is not null
        loop
            v_draw := case v_budget.counts
                when 'lots' then (v_earned ->> 'portion')::numeric
                when 'free_item' then coalesce(
                    (
                        select sum((f ->> 'qty')::numeric)
                        from jsonb_array_elements(v_earned -> 'free_items') as f
                        where coalesce((f ->> 'chosen')::boolean, false)
                    ),
                    0
                )
                else (v_earned ->> 'discount_amount')::numeric
            end;

            if v_draw > 0 then
                insert into sales_order_budget_draw (
                    order_id, budget_id, allocation_id, sequence_id, amount
                )
                values (
                    v_order_id,
                    v_budget.budget_id,
                    v_budget.allocation_id,
                    (v_earned ->> 'sequence_id')::uuid,
                    v_draw
                )
                on conflict (order_id, sequence_id) do nothing;

                update discount_budget_allocation
                set spent = spent + v_draw
                where id = v_budget.allocation_id;
            end if;
        end loop;

    end loop;

    -- -------------------------------------------------------------------------
    -- Manual discounts. Named by the client, valued by the catalogue.
    -- -------------------------------------------------------------------------

    -- The gross the percentage entries are taken on: goods sold, before VAT and
    -- before any automatic discount. Automatic and manual both come off the same
    -- figure, so applying one never quietly shrinks the other.
    select coalesce(sum(gross_amount), 0) into v_gross
    from sales_order_line where order_id = v_order_id and not is_free;

    for v_manual in
        select * from jsonb_array_elements(coalesce(p_order -> 'manual_promotions', '[]'::jsonb))
    loop
        select m.* into v_entry
        from manual_promotions_for(v_customer) m
        where m.promotion_id = (v_manual ->> 'promotion_id')::uuid;

        -- Not in the catalogue this outlet may draw on today: expired, another
        -- branch's, or invented. Refused rather than skipped â€” a rep who was
        -- shown it and applied it deserves to be told it did not take.
        if v_entry is null then
            raise exception
                'order %: manual promotion % is not available for this outlet',
                v_order_id, v_manual ->> 'promotion_id';
        end if;

        if v_entry.promo_type = 'percent' then
            v_amount := round(v_gross * v_entry.value / 100);
        elsif v_entry.promo_type = 'amount' then
            v_amount := v_entry.value::bigint;

            -- An editable entry is a ceiling the rep may come under. Anything
            -- above it is refused, not clamped: a rep who typed 500.000 and got
            -- 50.000 would find out from the customer.
            if v_manual ? 'amount' then
                if not v_entry.allow_edit then
                    raise exception
                        'order %: manual promotion % is not editable',
                        v_order_id, v_entry.code;
                end if;

                if (v_manual ->> 'amount')::bigint > v_entry.value then
                    raise exception
                        'order %: manual promotion % allows at most %, % asked for',
                        v_order_id, v_entry.code, v_entry.value,
                        v_manual ->> 'amount';
                end if;

                v_amount := greatest((v_manual ->> 'amount')::bigint, 0);
            end if;
        else
            v_amount := 0;
        end if;

        insert into sales_order_manual_discount (
            order_id, promotion_id, promo_type, discount_amount, percent, descr
        )
        values (
            v_order_id,
            v_entry.promotion_id,
            v_entry.promo_type,
            v_amount,
            case when v_entry.promo_type = 'percent' then v_entry.value end,
            v_entry.name
        )
        returning id into v_disc_id;

        -- Goods. Everything the entry lists, because a manual promotion has no
        -- alternatives â€” the rep already chose at the level above.
        if v_entry.promo_type = 'free_item' then
            for v_free in select * from jsonb_array_elements(v_entry.items)
            loop
                insert into sales_order_manual_discount_item (
                    manual_discount_id, product_id, uom_code, qty
                )
                values (
                    v_disc_id,
                    (v_free ->> 'product_id')::uuid,
                    (v_free ->> 'uom_code')::text,
                    (v_free ->> 'qty')::integer
                );

                v_next_line := v_next_line + 1;

                insert into sales_order_line (
                    order_id, line_no, product_id, uom_code, qty,
                    conversion_rate, base_qty, price, vat_basis_points,
                    gross_amount, discount_amount, vat_amount, line_amount, is_free
                )
                select
                    v_order_id,
                    v_next_line,
                    (v_free ->> 'product_id')::uuid,
                    (v_free ->> 'uom_code')::text,
                    (v_free ->> 'qty')::integer,
                    pu.conversion_rate,
                    (v_free ->> 'qty')::integer * pu.conversion_rate,
                    0, 0, 0, 0, 0, 0, true
                from product_uom pu
                where pu.product_id = (v_free ->> 'product_id')::uuid
                  and pu.uom_code = (v_free ->> 'uom_code')::text
                on conflict (order_id, product_id, uom_code, is_free) do update
                    set qty = sales_order_line.qty + excluded.qty,
                        base_qty = sales_order_line.base_qty + excluded.base_qty;
            end loop;
        end if;
    end loop;

    -- -------------------------------------------------------------------------
    -- Totals. Free lines contribute nothing; both kinds of discount come off the
    -- total the customer pays, never off the gross, so both figures stay the
    -- ones an invoice prints.
    -- -------------------------------------------------------------------------

    update sales_order o
    set sub_total       = t.sub_total,
        vat_amount      = t.vat_amount,
        discount_amount = coalesce(d.auto, 0) + coalesce(d.manual, 0),
        total_amount    = greatest(
            t.total_amount - coalesce(d.auto, 0) - coalesce(d.manual, 0), 0
        )
    from (
        select
            coalesce(sum(gross_amount), 0) as sub_total,
            coalesce(sum(vat_amount), 0)   as vat_amount,
            coalesce(sum(line_amount), 0)  as total_amount
        from sales_order_line
        where order_id = v_order_id and not is_free
    ) t
    left join lateral (
        select
            (
                select coalesce(sum(discount_amount), 0)
                from sales_order_discount where order_id = v_order_id
            ) as auto,
            (
                select coalesce(sum(discount_amount), 0)
                from sales_order_manual_discount where order_id = v_order_id
            ) as manual
    ) d on true
    where o.id = v_order_id;

    -- Same transaction as the order: the step is done because the order exists.
    insert into visit_step_result (visit_id, form_id, completed_at, payload)
    values (
        v_visit_id,
        'take_order',
        now(),
        jsonb_build_object('order_id', v_order_id, 'order_no', v_order_no)
    )
    on conflict (visit_id, form_id) do update
        set completed_at = excluded.completed_at,
            payload      = excluded.payload;

    return v_order_id;
end;
$function$
;

-- The engine tests, with the "or" gift case following the new default.
create or replace function test_calculate_promotions()
returns table (
    case_name text,
    passed    boolean,
    expected  text,
    actual    text
)
-- security definer, and narrowly granted. The campaign tables let a rep read
-- and nothing more, which is right; a harness that has to write fixtures cannot
-- run under those policies. So it runs as the owner and is executable only by
-- service_role — a developer tool, never something the app can reach.
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
    -- KH002: on the demo branch, with no audience rows anywhere pointing at it,
    -- so what it qualifies for is exactly what this function creates.
    c_cust  constant uuid := '00000000-0000-0000-0000-000000000092';

    -- Coca and Pepsi by the case, sweets by the pack. From the base catalogue
    -- rather than invented here: a test that mints its own products would not
    -- exercise the unit conversion, which is where the engine is subtlest.
    c_coca  constant uuid := '00000000-0000-0000-0000-000000000c01';
    c_pepsi constant uuid := '00000000-0000-0000-0000-000000000c02';
    c_water constant uuid := '00000000-0000-0000-0000-000000000c03';
    c_sweet constant uuid := '00000000-0000-0000-0000-000000000c08';

    c_prog  constant uuid := 'ffffffff-0000-0000-0000-000000000001';
    c_seq   constant uuid := 'ffffffff-0000-0000-0000-000000000002';
    c_seq2  constant uuid := 'ffffffff-0000-0000-0000-000000000003';
    c_hi    constant uuid := 'ffffffff-0000-0000-0000-000000000004';  -- level: 10
    c_lo    constant uuid := 'ffffffff-0000-0000-0000-000000000005';  -- level: 5
    c_b2    constant uuid := 'ffffffff-0000-0000-0000-000000000006';
    c_budget constant uuid := 'ffffffff-0000-0000-0000-000000000007';

    v_muted uuid[];
begin
    -- Everything from here to the matching restore runs inside one block whose
    -- exception handler puts the catalogue back. A test that leaves the demo
    -- campaigns switched off would be worse than no test.
    begin
        select coalesce(array_agg(id), '{}') into v_muted
        from discount_sequence where is_active;

        update discount_sequence set is_active = false where id = any (v_muted);

        insert into discount_program (id, code, name, scope, is_active)
        values (c_prog, 'ZZ-TEST', 'Chương trình kiểm thử', 'line', true);

        insert into discount_sequence (
            id, program_id, code, name, break_by, reward,
            from_date, to_date, is_active
        )
        values (
            c_seq, c_prog, 'S1', 'Mức kiểm thử', 'qty', 'amount',
            current_date - 1, current_date + 1, true
        );

        insert into discount_condition_item (sequence_id, product_id, uom_code)
        values (c_seq, c_coca, 'CASE');

        insert into discount_break (id, sequence_id, line_ref, name, break_qty, disc_amt)
        values
            (c_hi, c_seq, 'B1', 'Mua 10', 10, 100000),
            (c_lo, c_seq, 'B2', 'Mua 5',   5,  40000);

        -- =====================================================================
        -- The ladder
        -- =====================================================================

        case_name := 'bậc thang: 12 thùng ăn mức 10 một suất';
        expected  := 'Mua 10|1';
        select coalesce(string_agg((e ->> 'break_name') || '|' || (e ->> 'portion'), ','), '-')
        into actual
        from jsonb_array_elements(
            calculate_promotions(c_cust, jsonb_build_array(
                jsonb_build_object('product_id', c_coca, 'uom_code', 'CASE', 'qty', 12))
            ) -> 'earned') e;
        passed := actual = expected;
        return next;

        case_name := 'bậc thang lặp: 30 thùng ăn mức 10 ba suất';
        expected  := 'Mua 10|3';
        select coalesce(string_agg((e ->> 'break_name') || '|' || (e ->> 'portion'), ','), '-')
        into actual
        from jsonb_array_elements(
            calculate_promotions(c_cust, jsonb_build_array(
                jsonb_build_object('product_id', c_coca, 'uom_code', 'CASE', 'qty', 30))
            ) -> 'earned') e;
        passed := actual = expected;
        return next;

        case_name := 'phần dư rơi xuống mức thấp hơn';
        expected  := 'Mua 10|1,Mua 5|1';
        select coalesce(string_agg((e ->> 'break_name') || '|' || (e ->> 'portion'), ','), '-')
        into actual
        from jsonb_array_elements(
            calculate_promotions(c_cust, jsonb_build_array(
                jsonb_build_object('product_id', c_coca, 'uom_code', 'CASE', 'qty', 17))
            ) -> 'earned') e;
        passed := actual = expected;
        return next;

        -- Ten cases is the level; a case short of it is nothing. The boundary is
        -- where an off-by-one lives.
        case_name := 'dưới ngưỡng thấp nhất thì không ăn';
        expected  := '-';
        select coalesce(string_agg(e ->> 'break_name', ','), '-')
        into actual
        from jsonb_array_elements(
            calculate_promotions(c_cust, jsonb_build_array(
                jsonb_build_object('product_id', c_coca, 'uom_code', 'CASE', 'qty', 4))
            ) -> 'earned') e;
        passed := actual = expected;
        return next;

        -- =====================================================================
        -- Unit conversion: the rule is written in cases, the basket in pieces
        -- =====================================================================

        case_name := 'quy đổi đơn vị: 240 lẻ = 10 thùng';
        expected  := 'Mua 10|1';
        select coalesce(string_agg((e ->> 'break_name') || '|' || (e ->> 'portion'), ','), '-')
        into actual
        from jsonb_array_elements(
            calculate_promotions(c_cust, jsonb_build_array(
                jsonb_build_object('product_id', c_coca, 'uom_code', 'PCS', 'qty', 240))
            ) -> 'earned') e;
        passed := actual = expected;
        return next;

        -- =====================================================================
        -- MaxLot
        -- =====================================================================

        update discount_break set max_lot = 2 where id = c_hi;

        case_name := 'MaxLot chặn số suất';
        expected  := '2';
        select coalesce(max(e ->> 'portion'), '-')
        into actual
        from jsonb_array_elements(
            calculate_promotions(c_cust, jsonb_build_array(
                jsonb_build_object('product_id', c_coca, 'uom_code', 'CASE', 'qty', 30))
            ) -> 'earned') e
        where e ->> 'break_name' = 'Mua 10';
        passed := actual = expected;
        return next;

        update discount_break set max_lot = 0 where id = c_hi;

        -- =====================================================================
        -- BreakBoundType
        -- =====================================================================

        update discount_sequence set break_bound = 'bounded' where id = c_seq;

        case_name := 'bounded: một suất và không xét mức thấp hơn';
        expected  := 'Mua 10|1';
        select coalesce(string_agg((e ->> 'break_name') || '|' || (e ->> 'portion'), ','), '-')
        into actual
        from jsonb_array_elements(
            calculate_promotions(c_cust, jsonb_build_array(
                jsonb_build_object('product_id', c_coca, 'uom_code', 'CASE', 'qty', 30))
            ) -> 'earned') e;
        passed := actual = expected;
        return next;

        update discount_break set break_qty_upper = 15 where id = c_hi;

        case_name := 'dải trên: vượt trần thì rơi xuống mức dưới';
        expected  := 'Mua 5';
        select coalesce(string_agg(e ->> 'break_name', ','), '-')
        into actual
        from jsonb_array_elements(
            calculate_promotions(c_cust, jsonb_build_array(
                jsonb_build_object('product_id', c_coca, 'uom_code', 'CASE', 'qty', 20))
            ) -> 'earned') e;
        passed := actual = expected;
        return next;

        update discount_break set break_qty_upper = 0 where id = c_hi;
        update discount_sequence set break_bound = 'lower' where id = c_seq;

        -- =====================================================================
        -- OnlyMaxLineRef and ExactQty
        -- =====================================================================

        update discount_sequence set only_once = true where id = c_seq;

        case_name := 'chỉ một lần mỗi đơn';
        expected  := '1';
        select count(*)::text into actual
        from jsonb_array_elements(
            calculate_promotions(c_cust, jsonb_build_array(
                jsonb_build_object('product_id', c_coca, 'uom_code', 'CASE', 'qty', 17))
            ) -> 'earned') e;
        passed := actual = expected;
        return next;

        update discount_sequence set only_once = false, exact_qty = true where id = c_seq;

        case_name := 'đúng chằn: 13 thùng không ăn';
        expected  := '-';
        select coalesce(string_agg(e ->> 'break_name', ','), '-')
        into actual
        from jsonb_array_elements(
            calculate_promotions(c_cust, jsonb_build_array(
                jsonb_build_object('product_id', c_coca, 'uom_code', 'CASE', 'qty', 13))
            ) -> 'earned') e;
        passed := actual = expected;
        return next;

        case_name := 'đúng chằn: 10 thùng ăn mức 10';
        expected  := 'Mua 10';
        select coalesce(string_agg(e ->> 'break_name', ','), '-')
        into actual
        from jsonb_array_elements(
            calculate_promotions(c_cust, jsonb_build_array(
                jsonb_build_object('product_id', c_coca, 'uom_code', 'CASE', 'qty', 10))
            ) -> 'earned') e;
        passed := actual = expected;
        return next;

        update discount_sequence set exact_qty = false where id = c_seq;

        -- =====================================================================
        -- Required items — the check a hand test let through once already
        -- =====================================================================

        update discount_sequence set required_type = 'qty' where id = c_seq;
        update discount_condition_item set required_value = 4 where sequence_id = c_seq;
        insert into discount_condition_item (sequence_id, product_id, uom_code, required_value)
        values (c_seq, c_pepsi, 'CASE', 4);

        case_name := 'SP bắt buộc vắng mặt thì cả CT không ăn';
        expected  := '-';
        select coalesce(string_agg(e ->> 'break_name', ','), '-')
        into actual
        from jsonb_array_elements(
            calculate_promotions(c_cust, jsonb_build_array(
                jsonb_build_object('product_id', c_coca, 'uom_code', 'CASE', 'qty', 12))
            ) -> 'earned') e;
        passed := actual = expected;
        return next;

        -- Four cases of Pepsi, not five: it satisfies the requirement without
        -- reaching a level of its own. A line rule measures each condition item
        -- separately, so five would have earned the lower level on Pepsi too and
        -- the case would have been testing two things at once.
        case_name := 'đủ mọi SP bắt buộc thì ăn';
        expected  := 'Mua 10';
        select coalesce(string_agg(e ->> 'break_name', ','), '-')
        into actual
        from jsonb_array_elements(
            calculate_promotions(c_cust, jsonb_build_array(
                jsonb_build_object('product_id', c_coca, 'uom_code', 'CASE', 'qty', 12),
                jsonb_build_object('product_id', c_pepsi, 'uom_code', 'CASE', 'qty', 4))
            ) -> 'earned') e;
        passed := actual = expected;
        return next;

        -- "Buy any N of these": the values stop mattering, the count starts.
        update discount_sequence set required_type = 'any_n', required_number = 3
        where id = c_seq;
        insert into discount_condition_item (sequence_id, product_id, uom_code)
        values (c_seq, c_water, 'CASE');

        case_name := 'bất kỳ N: hai mặt hàng thì chưa đủ';
        expected  := '-';
        select coalesce(string_agg(e ->> 'break_name', ','), '-')
        into actual
        from jsonb_array_elements(
            calculate_promotions(c_cust, jsonb_build_array(
                jsonb_build_object('product_id', c_coca, 'uom_code', 'CASE', 'qty', 12),
                jsonb_build_object('product_id', c_pepsi, 'uom_code', 'CASE', 'qty', 4))
            ) -> 'earned') e;
        passed := actual = expected;
        return next;

        case_name := 'bất kỳ N: đủ ba mặt hàng thì ăn';
        expected  := 'Mua 10';
        select coalesce(string_agg(e ->> 'break_name', ','), '-')
        into actual
        from jsonb_array_elements(
            calculate_promotions(c_cust, jsonb_build_array(
                jsonb_build_object('product_id', c_coca, 'uom_code', 'CASE', 'qty', 12),
                jsonb_build_object('product_id', c_pepsi, 'uom_code', 'CASE', 'qty', 4),
                jsonb_build_object('product_id', c_water, 'uom_code', 'CASE', 'qty', 1))
            ) -> 'earned') e;
        passed := actual = expected;
        return next;

        delete from discount_condition_item
        where sequence_id = c_seq and product_id in (c_pepsi, c_water);
        update discount_sequence set required_type = 'none', required_number = 0
        where id = c_seq;
        update discount_condition_item set required_value = 0 where sequence_id = c_seq;

        -- =====================================================================
        -- Percentages: a threshold, not a multiplier, on what is still charged
        -- =====================================================================

        update discount_sequence set reward = 'percent' where id = c_seq;
        update discount_break set disc_amt = 5 where id = c_hi;

        -- 30 cases clears the level three times. A percentage does not deepen.
        case_name := '% không nhân theo số suất';
        expected  := '5.0000|342000';
        select coalesce(max((e ->> 'percent') || '|' || (e ->> 'discount_amount')), '-')
        into actual
        from jsonb_array_elements(
            calculate_promotions(c_cust, jsonb_build_array(
                jsonb_build_object('product_id', c_coca, 'uom_code', 'CASE', 'qty', 30))
            ) -> 'earned') e;
        passed := actual = expected;
        return next;

        -- A money rule on the same product first, then the percentage: the base
        -- has shrunk by what was already given back.
        insert into discount_sequence (
            id, program_id, code, name, break_by, reward, priority,
            from_date, to_date, is_active
        )
        values (
            c_seq2, c_prog, 'S2', 'Giảm tiền trước', 'qty', 'amount', 99,
            current_date - 1, current_date + 1, true
        );
        insert into discount_condition_item (sequence_id, product_id, uom_code)
        values (c_seq2, c_coca, 'CASE');
        insert into discount_break (id, sequence_id, line_ref, name, break_qty, disc_amt)
        values (c_b2, c_seq2, 'B1', 'Giảm tiền', 10, 100000);

        -- 12 cases: 2.736.000 gross, less 100.000 already given, times 5%.
        case_name := '% tính trên số tiền sau giảm';
        expected  := '131800';
        select coalesce(max(e ->> 'discount_amount'), '-')
        into actual
        from jsonb_array_elements(
            calculate_promotions(c_cust, jsonb_build_array(
                jsonb_build_object('product_id', c_coca, 'uom_code', 'CASE', 'qty', 12))
            ) -> 'earned') e
        where e ->> 'sequence_code' = 'S1';
        passed := actual = expected;
        return next;

        -- =====================================================================
        -- ExcludePromo: firing one silences another
        -- =====================================================================

        insert into discount_exclusion (sequence_id, excludes_id) values (c_seq2, c_seq);

        case_name := 'CT nổ trước loại CT bị cấm';
        expected  := 'S2';
        select coalesce(string_agg(e ->> 'sequence_code', ','), '-')
        into actual
        from jsonb_array_elements(
            calculate_promotions(c_cust, jsonb_build_array(
                jsonb_build_object('product_id', c_coca, 'uom_code', 'CASE', 'qty', 12))
            ) -> 'earned') e;
        passed := actual = expected;
        return next;

        delete from discount_exclusion where sequence_id = c_seq2;

        -- =====================================================================
        -- IsDeductQtyAmt: which basket a rule is measured against
        -- =====================================================================

        -- S2 fires first and eats ten cases; S1 measured against the shared pool
        -- then sees two and cannot reach its own level of ten.
        update discount_sequence set reward = 'amount', deducts_shared = true where id = c_seq;
        update discount_break set disc_amt = 100000 where id = c_hi;

        case_name := 'đo trên bể chung: CT sau thấy phần còn lại';
        expected  := 'S2';
        select coalesce(string_agg(e ->> 'sequence_code', ','), '-')
        into actual
        from jsonb_array_elements(
            calculate_promotions(c_cust, jsonb_build_array(
                jsonb_build_object('product_id', c_coca, 'uom_code', 'CASE', 'qty', 12))
            ) -> 'earned') e;
        passed := actual = expected;
        return next;

        case_name := 'đo trên giỏ gốc: cả hai CT cùng ăn';
        expected  := 'S1,S2';
        update discount_sequence set deducts_shared = false where id = c_seq;
        select coalesce(string_agg(e ->> 'sequence_code', ',' order by e ->> 'sequence_code'), '-')
        into actual
        from jsonb_array_elements(
            calculate_promotions(c_cust, jsonb_build_array(
                jsonb_build_object('product_id', c_coca, 'uom_code', 'CASE', 'qty', 12))
            ) -> 'earned') e;
        passed := actual = expected;
        return next;

        delete from discount_break where id = c_b2;
        delete from discount_condition_item where sequence_id = c_seq2;
        delete from discount_sequence where id = c_seq2;

        -- =====================================================================
        -- Gifts
        -- =====================================================================

        update discount_sequence set reward = 'free_item' where id = c_seq;
        insert into discount_free_item (sequence_id, product_id, uom_code, free_qty, sort_order)
        values
            (c_seq, c_pepsi, 'CASE', 2, 1),
            (c_seq, c_sweet, 'PACK', 1, 2);

        case_name := 'quà nhân theo số suất';
        expected  := 'NGK002|4,BK003|2';
        select coalesce(string_agg((f ->> 'product_code') || '|' || (f ->> 'qty'), ','), '-')
        into actual
        from jsonb_array_elements(
            calculate_promotions(c_cust, jsonb_build_array(
                jsonb_build_object('product_id', c_coca, 'uom_code', 'CASE', 'qty', 20))
            ) -> 'earned') e,
            jsonb_array_elements(e -> 'free_items') f;
        passed := actual = expected;
        return next;

        case_name := 'tên đơn vị đi kèm mã đơn vị';
        expected  := 'CASE|Thung';
        select coalesce(max((f ->> 'uom_code') || '|' || (f ->> 'uom_name')), '-')
        into actual
        from jsonb_array_elements(
            calculate_promotions(c_cust, jsonb_build_array(
                jsonb_build_object('product_id', c_coca, 'uom_code', 'CASE', 'qty', 10))
            ) -> 'earned') e,
            jsonb_array_elements(e -> 'free_items') f
        where f ->> 'product_code' = 'NGK002';
        passed := actual = expected;
        return next;

        -- A list of alternatives pre-selects one and only one. Which one
        -- follows the BA's rule (stock, then what the order sells), which
        -- supabase/tests/06_promotion_default_gift.sql checks on its own.
        update discount_sequence set auto_free_item = false where id = c_seq;

        case_name := 'quà OR: chỉ một món được chọn sẵn';
        expected  := '2|1';
        select count(*) || '|' || count(*) filter (where (f ->> 'chosen')::boolean)
        into actual
        from jsonb_array_elements(
            calculate_promotions(c_cust, jsonb_build_array(
                jsonb_build_object('product_id', c_coca, 'uom_code', 'CASE', 'qty', 10))
            ) -> 'earned') e,
            jsonb_array_elements(e -> 'free_items') f;
        passed := actual = expected;
        return next;

        update discount_sequence set auto_free_item = true, donate_group = true where id = c_seq;

        case_name := 'chia đều: bể 3 chia cho 2 món';
        expected  := 'NGK002|1,BK003|1';
        select coalesce(string_agg((f ->> 'product_code') || '|' || (f ->> 'qty'), ','), '-')
        into actual
        from jsonb_array_elements(
            calculate_promotions(c_cust, jsonb_build_array(
                jsonb_build_object('product_id', c_coca, 'uom_code', 'CASE', 'qty', 10))
            ) -> 'earned') e,
            jsonb_array_elements(e -> 'free_items') f;
        passed := actual = expected;
        return next;

        update discount_sequence set donate_group = false, reward = 'amount',
            convert_amount_to_goods = true where id = c_seq;
        update discount_break set disc_amt = 500000 where id = c_hi;
        delete from discount_free_item where sequence_id = c_seq and product_id = c_sweet;
        update discount_free_item set promo_price = 200000 where sequence_id = c_seq;

        case_name := 'quy tiền ra hàng: 500k ở giá 200k = 2, tiền về 0';
        expected  := '0|NGK002|2';
        select coalesce(max((e ->> 'discount_amount') || '|' || (f ->> 'product_code')
                            || '|' || (f ->> 'qty')), '-')
        into actual
        from jsonb_array_elements(
            calculate_promotions(c_cust, jsonb_build_array(
                jsonb_build_object('product_id', c_coca, 'uom_code', 'CASE', 'qty', 10))
            ) -> 'earned') e,
            jsonb_array_elements(e -> 'free_items') f;
        passed := actual = expected;
        return next;

        update discount_sequence set convert_amount_to_goods = false, reward = 'free_item'
        where id = c_seq;
        update discount_free_item set promo_price = 0 where sequence_id = c_seq;

        -- =====================================================================
        -- Budget
        -- =====================================================================

        update discount_sequence set reward = 'amount' where id = c_seq;
        update discount_break set disc_amt = 100000 where id = c_hi;

        insert into discount_budget (id, code, name, counts, alloc_by, from_date, to_date)
        values (c_budget, 'ZZ-NS', 'Ngân sách kiểm thử', 'amount', 'route',
                current_date - 1, current_date + 1);

        insert into discount_budget_allocation (budget_id, target_id, allocated, spent)
        select c_budget, r.id, 50000, 0
        from sales_route r
        where r.salesperson_id = current_salesperson_id() and r.is_active
        order by r.code limit 1;

        update discount_sequence set budget_id = c_budget where id = c_seq;

        -- OM10100LevelCap: the 10-case level (100.000 đ) does not fit the
        -- 50.000 đ budget, so its ten cases fall to the 5-case level, of
        -- which the budget pays one lot (40.000 đ) rather than two.
        case_name := 'hết ngân sách ở mức cao thì phần dư xuống mức thấp, trong ngân sách';
        expected  := 'Mua 5 x1';
        select coalesce(string_agg((e ->> 'break_name') || ' x' || (e ->> 'portion'), ','), '-')
        into actual
        from jsonb_array_elements(
            calculate_promotions(c_cust, jsonb_build_array(
                jsonb_build_object('product_id', c_coca, 'uom_code', 'CASE', 'qty', 10))
            ) -> 'earned') e;
        passed := actual = expected;
        return next;

        case_name := 'và được báo lại kèm số muốn / số còn';
        expected  := '100000|50000.0000';
        select coalesce(max((o ->> 'wanted') || '|' || (o ->> 'remaining')), '-')
        into actual
        from jsonb_array_elements(
            calculate_promotions(c_cust, jsonb_build_array(
                jsonb_build_object('product_id', c_coca, 'uom_code', 'CASE', 'qty', 10))
            ) -> 'out_of_budget') o;
        passed := actual = expected;
        return next;

        update discount_budget_allocation set allocated = 500000 where budget_id = c_budget;

        case_name := 'ngân sách đủ thì CT áp dụng lại';
        expected  := 'S1';
        select coalesce(string_agg(e ->> 'sequence_code', ','), '-')
        into actual
        from jsonb_array_elements(
            calculate_promotions(c_cust, jsonb_build_array(
                jsonb_build_object('product_id', c_coca, 'uom_code', 'CASE', 'qty', 10))
            ) -> 'earned') e;
        passed := actual = expected;
        return next;

        -- =====================================================================
        -- Combo: the ladder counts complete bundles
        -- =====================================================================

        -- One bundle is 2 Coca and 1 Pepsi. Levels are read as bundle counts, so
        -- "Mua 10" here means ten bundles.
        update discount_sequence
        set is_bundle = true, reward = 'amount', budget_id = null
        where id = c_seq;
        update discount_condition_item set bundle_qty = 2 where sequence_id = c_seq;
        insert into discount_condition_item (sequence_id, product_id, uom_code, bundle_qty)
        values (c_seq, c_pepsi, 'CASE', 1);
        update discount_break set break_qty = 3, name = 'Ba bộ' where id = c_hi;
        update discount_break set break_qty = 1, name = 'Một bộ' where id = c_lo;

        -- 12 Coca makes 6, 4 Pepsi makes 4: four bundles, so the three-bundle
        -- level once, with one bundle left for the one-bundle level.
        case_name := 'combo: số bộ là min của các tỉ lệ';
        expected  := 'Ba bộ|1,Một bộ|1';
        select coalesce(string_agg((e ->> 'break_name') || '|' || (e ->> 'portion'), ','), '-')
        into actual
        from jsonb_array_elements(
            calculate_promotions(c_cust, jsonb_build_array(
                jsonb_build_object('product_id', c_coca, 'uom_code', 'CASE', 'qty', 12),
                jsonb_build_object('product_id', c_pepsi, 'uom_code', 'CASE', 'qty', 4))
            ) -> 'earned') e;
        passed := actual = expected;
        return next;

        case_name := 'combo: thiếu một thành phần thì không có bộ nào';
        expected  := '-';
        select coalesce(string_agg(e ->> 'break_name', ','), '-')
        into actual
        from jsonb_array_elements(
            calculate_promotions(c_cust, jsonb_build_array(
                jsonb_build_object('product_id', c_coca, 'uom_code', 'CASE', 'qty', 30))
            ) -> 'earned') e;
        passed := actual = expected;
        return next;

        -- Six Coca and three Pepsi is three bundles of each, evenly. Seven Coca
        -- is three bundles and one case spare, which an exact rule refuses.
        update discount_sequence set exact_qty = true where id = c_seq;

        case_name := 'combo đúng chằn: chia hết và bằng nhau thì ăn';
        expected  := 'Ba bộ';
        select coalesce(string_agg(e ->> 'break_name', ','), '-')
        into actual
        from jsonb_array_elements(
            calculate_promotions(c_cust, jsonb_build_array(
                jsonb_build_object('product_id', c_coca, 'uom_code', 'CASE', 'qty', 6),
                jsonb_build_object('product_id', c_pepsi, 'uom_code', 'CASE', 'qty', 3))
            ) -> 'earned') e;
        passed := actual = expected;
        return next;

        case_name := 'combo đúng chằn: dư một thành phần thì không ăn';
        expected  := '-';
        select coalesce(string_agg(e ->> 'break_name', ','), '-')
        into actual
        from jsonb_array_elements(
            calculate_promotions(c_cust, jsonb_build_array(
                jsonb_build_object('product_id', c_coca, 'uom_code', 'CASE', 'qty', 7),
                jsonb_build_object('product_id', c_pepsi, 'uom_code', 'CASE', 'qty', 3))
            ) -> 'earned') e;
        passed := actual = expected;
        return next;

        update discount_sequence set exact_qty = false, is_bundle = false where id = c_seq;
        delete from discount_condition_item where sequence_id = c_seq and product_id = c_pepsi;
        update discount_condition_item set bundle_qty = 0 where sequence_id = c_seq;
        update discount_break set break_qty = 10, name = 'Mua 10' where id = c_hi;
        update discount_break set break_qty = 5, name = 'Mua 5' where id = c_lo;

        -- =====================================================================
        -- An empty basket earns nothing and does not fall over
        -- =====================================================================

        case_name := 'giỏ rỗng: không ăn gì, không lỗi';
        expected  := '0|0';
        select (t.r ->> 'total_discount') || '|' ||
               jsonb_array_length(t.r -> 'earned')::text
        into actual
        from (select calculate_promotions(c_cust, '[]'::jsonb) as r) t;
        passed := actual = expected;
        return next;

        -- ---------------------------------------------------------------------
        -- Put everything back, whatever happened above.
        -- ---------------------------------------------------------------------
        -- The sequence points at the budget, so the reference goes first.
        update discount_sequence set budget_id = null where program_id = c_prog;
        delete from discount_budget where id = c_budget;
        delete from discount_free_item where sequence_id = c_seq;
        delete from discount_break where sequence_id in (c_seq, c_seq2);
        delete from discount_condition_item where sequence_id in (c_seq, c_seq2);
        delete from discount_sequence where program_id = c_prog;
        delete from discount_program where id = c_prog;
        update discount_sequence set is_active = true where id = any (v_muted);

    exception when others then
        -- The catalogue matters more than the diagnosis. Restore first, then let
        -- the error out with its own message intact.
        -- The sequence points at the budget, so the reference goes first.
        update discount_sequence set budget_id = null where program_id = c_prog;
        delete from discount_budget where id = c_budget;
        delete from discount_free_item where sequence_id = c_seq;
        delete from discount_break where sequence_id in (c_seq, c_seq2);
        delete from discount_condition_item where sequence_id in (c_seq, c_seq2);
        delete from discount_sequence where program_id = c_prog;
        delete from discount_program where id = c_prog;
        update discount_sequence set is_active = true where id = any (v_muted);
        raise;
    end;
end;
$$;

