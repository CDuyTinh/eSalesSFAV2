-- =============================================================================
-- Booking an order with its promotions
--
-- The rule the rest of this schema already follows: the server decides money.
-- The device sends what the rep chose — product, unit, quantity, and which gift
-- they picked where a rule offers a choice — and `submit_order` recomputes the
-- promotions itself. A client that could name its own discount is a client that
-- could book a free order, and a device that has been in a bag since Tuesday is
-- a device that does not know which campaigns ended on Wednesday.
--
-- The choice half does come from the client, because nobody else can make it:
-- when a rule says "30.000 đ or a box of Oreo", only the person standing in the
-- shop knows which the customer wants. The server honours the choice and
-- recomputes everything around it, so a forged choice can pick a different gift
-- from the same rule but cannot invent a rule, a level or an amount.
--
--   `promotions`: [ { "sequence_id": …, "free_product_id": …, "free_uom_code": …,
--                     "take_amount": true|false } ]
--
-- Free goods become order lines at zero, because they are picked, loaded and
-- delivered like everything else. They are excluded from sub_total and vat, and
-- carry `is_free`.
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
    -- Promotions, recomputed here from the lines just booked.
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

                -- The delivery line. Zero everything: it is given, not sold, and
                -- it must not move sub_total, VAT or the total the customer owes.
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
    -- Totals. Free lines contribute nothing; the discount comes off the total
    -- the customer pays, never off the gross, so both figures stay readable on
    -- an invoice.
    -- -------------------------------------------------------------------------

    update sales_order o
    set sub_total       = t.sub_total,
        vat_amount      = t.vat_amount,
        discount_amount = coalesce(d.discount_amount, 0),
        total_amount    = greatest(t.total_amount - coalesce(d.discount_amount, 0), 0)
    from (
        select
            coalesce(sum(gross_amount), 0) as sub_total,
            coalesce(sum(vat_amount), 0)   as vat_amount,
            coalesce(sum(line_amount), 0)  as total_amount
        from sales_order_line
        where order_id = v_order_id and not is_free
    ) t
    left join lateral (
        select coalesce(sum(discount_amount), 0) as discount_amount
        from sales_order_discount
        where order_id = v_order_id
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
