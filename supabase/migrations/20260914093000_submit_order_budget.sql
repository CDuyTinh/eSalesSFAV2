-- =============================================================================
-- Spending the budget when the order books
--
-- The engine already refuses a rule whose pot cannot cover it, so nothing here
-- decides anything: what is left is the spending itself, recorded and deducted
-- in one breath.
--
-- Booked per order and per rule rather than per level. The pot is a limit on
-- what a route or an outlet may be given; it is not a limit on how that giving
-- happened to be arranged into levels, and a rule that earned three levels of
-- one campaign has drawn once from one campaign's money.
--
-- `sales_order_budget_draw` keeps the row rather than leaving `spent` as a bare
-- running total, so a cancelled order can be put back later without anyone
-- having to guess which pot it came out of.
-- =============================================================================

create or replace function submit_order(p_order jsonb)
returns uuid
language plpgsql
security invoker
set search_path = public, pg_temp
as $$
declare
    v_order_id   uuid := (p_order ->> 'id')::uuid;
    v_visit_id   uuid := (p_order ->> 'visit_id')::uuid;
    v_order_date date := coalesce((p_order ->> 'order_date')::date, current_date);
    v_sp_id      uuid := current_salesperson_id();
    v_sp_code    text;
    v_branch_id  uuid;
    v_customer   uuid;
    v_class_id   uuid;
    v_order_no   text;
    v_expected   integer;
    v_inserted   integer;
    v_promo      jsonb;
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
    where v.id = v_visit_id and v.salesperson_id = v_sp_id;

    if v_customer is null then
        raise exception 'visit % is not an open visit of this salesperson', v_visit_id;
    end if;

    -- Derived from the order id, so a retry produces the same number instead of
    -- burning a sequence value per attempt.
    v_order_no := v_sp_code || '-' || to_char(v_order_date, 'YYYYMMDD') || '-' ||
                  upper(substr(replace(v_order_id::text, '-', ''), 1, 6));

    insert into sales_order (
        id, order_no, customer_id, salesperson_id, branch_id, visit_id,
        order_date, note, client_total_amount, client_created_at
    )
    values (
        v_order_id, v_order_no, v_customer, v_sp_id, v_branch_id, v_visit_id,
        v_order_date,
        nullif(p_order ->> 'note', ''),
        coalesce((p_order ->> 'client_total_amount')::bigint, 0),
        coalesce((p_order ->> 'client_created_at')::timestamptz, now())
    )
    on conflict (id) do nothing;

    if not found then
        -- Replayed from the outbox; the first delivery already booked it.
        return v_order_id;
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
        v_order_date
    );

    select coalesce(max(line_no), 0) into v_next_line
    from sales_order_line where order_id = v_order_id;

    for v_earned in select * from jsonb_array_elements(v_promo -> 'earned')
    loop
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
        -- branch's, or invented. Refused rather than skipped — a rep who was
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
        -- alternatives — the rep already chose at the level above.
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
$$;

revoke execute on function submit_order(jsonb) from public;
grant execute on function submit_order(jsonb) to authenticated;
