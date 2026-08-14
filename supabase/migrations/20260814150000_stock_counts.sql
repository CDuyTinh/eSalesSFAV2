-- =============================================================================
-- Outlet stock counts
--
-- Backs the `stock_outlet` step: the rep counts what is physically on the
-- shelves before deciding what to order.
--
-- The count only earns its keep if the rep can see it against the last one, so
-- every line carries `prev_base_qty` — the same product's figure from this
-- customer's most recent earlier count. The drop between the two is what sold,
-- and that is the number an order is actually built from. `submit_stock_count`
-- fills it server-side rather than trusting the device, because the device may
-- have failed to load it and a wrong "previously" is worse than none.
--
-- Deliberately absent: `suggest_qty`. The legacy model stores a suggested order
-- quantity per line, but a suggestion needs a par level to aim at, and par
-- levels live in the must-stock-list tables (msl_headers / msl_items) that this
-- rebuild has not built. Computing a suggestion with no target would mean
-- inventing the one number a rep is most likely to trust blindly. What sold
-- since the last count is derivable and honest, so that is what is shown.
-- =============================================================================

create table stock_count (
    -- Client-minted, as for sales_order: it is the idempotency key that makes an
    -- outbox replay a no-op instead of a second count.
    id                uuid primary key,

    visit_id          uuid        not null references visit (id) on delete cascade,
    customer_id       uuid        not null references customer (id),
    salesperson_id    uuid        not null references salesperson (id),
    count_date        date        not null,
    note              text,

    -- Denormalised so the workflow screen can say "42 mat hang" without pulling
    -- every line back.
    line_count        integer     not null default 0,

    client_created_at timestamptz not null,
    created_at        timestamptz not null default now(),
    updated_at        timestamptz not null default now(),

    -- One count per visit. A rep who recounts is correcting the first attempt,
    -- not adding to it, and two counts for one visit would make "what sold"
    -- ambiguous for the next visit.
    unique (visit_id)
);

create index on stock_count (customer_id, count_date desc);
create index on stock_count (salesperson_id, count_date desc);

create table stock_count_line (
    id              uuid    primary key default gen_random_uuid(),
    stock_count_id  uuid    not null references stock_count (id) on delete cascade,

    product_id      uuid    not null references product (id),
    uom_code        text    not null references uom (code),

    -- Zero is a real count, not a missing one: "I looked and there were none" is
    -- an out-of-stock report, which is one of the more valuable things a rep
    -- brings back. Hence >= 0 here, where an order line demands > 0.
    qty             integer not null constraint stock_qty_not_negative check (qty >= 0),

    conversion_rate integer not null,
    base_qty        integer not null,

    -- This product's total from the customer's previous count, in base units.
    prev_base_qty   integer not null default 0,

    unique (stock_count_id, product_id, uom_code)
);

create index on stock_count_line (stock_count_id);
create index on stock_count_line (product_id);

create trigger stock_count_set_updated_at
    before update on stock_count
    for each row execute function set_updated_at();

-- -----------------------------------------------------------------------------
-- submit_stock_count
--
-- Same shape as submit_order: one transaction for the header, the lines and the
-- workflow step, keyed on the client's id. security invoker, so every write
-- below still passes the policies at the bottom of this file.
-- -----------------------------------------------------------------------------

create or replace function submit_stock_count(p_count jsonb)
returns uuid
language plpgsql
security invoker
set search_path = public, pg_temp
as $$
declare
    v_count_id   uuid := (p_count ->> 'id')::uuid;
    v_visit_id   uuid := (p_count ->> 'visit_id')::uuid;
    v_count_date date := coalesce((p_count ->> 'count_date')::date, current_date);
    v_sp_id      uuid := current_salesperson_id();
    v_customer   uuid;
    v_expected   integer;
    v_inserted   integer;
begin
    if v_count_id is null or v_visit_id is null then
        raise exception 'submit_stock_count needs both id and visit_id';
    end if;

    -- Already booked: a replay from the outbox, so nothing to do.
    if exists (select 1 from stock_count where id = v_count_id) then
        return v_count_id;
    end if;

    -- The customer comes from the visit, never the payload. This also fails
    -- closed on another rep's visit, which they cannot read.
    select v.customer_id into v_customer
    from visit v
    where v.id = v_visit_id and v.salesperson_id = v_sp_id;

    if v_customer is null then
        raise exception 'visit % is not an open visit of this salesperson', v_visit_id;
    end if;

    select count(*) into v_expected
    from jsonb_array_elements(p_count -> 'lines');

    if v_expected = 0 then
        raise exception 'stock count % has no lines', v_count_id;
    end if;

    -- A recount replaces the visit's earlier attempt. Done before the previous
    -- figures are read below, so a recount compares against the *last visit*
    -- rather than against the count it is replacing.
    delete from stock_count where visit_id = v_visit_id;

    insert into stock_count (
        id, visit_id, customer_id, salesperson_id, count_date, note,
        client_created_at
    )
    values (
        v_count_id, v_visit_id, v_customer, v_sp_id, v_count_date,
        nullif(p_count ->> 'note', ''),
        coalesce((p_count ->> 'client_created_at')::timestamptz, now())
    );

    with input as (
        select
            (l ->> 'product_id')::uuid as product_id,
            (l ->> 'uom_code')::text   as uom_code,
            (l ->> 'qty')::integer     as qty
        from jsonb_array_elements(p_count -> 'lines') as l
    ),
    -- The customer's most recent earlier count, totalled per product. Summed
    -- because one product may have been counted in two units — loose and by the
    -- case — and the comparison only means anything in base units.
    previous as (
        select scl.product_id, sum(scl.base_qty)::integer as prev_base_qty
        from stock_count sc
        join stock_count_line scl on scl.stock_count_id = sc.id
        where sc.customer_id = v_customer
          and sc.id = (
              select prior.id
              from stock_count prior
              where prior.customer_id = v_customer
                and prior.id <> v_count_id
              order by prior.count_date desc, prior.created_at desc
              limit 1
          )
        group by scl.product_id
    )
    insert into stock_count_line (
        stock_count_id, product_id, uom_code, qty,
        conversion_rate, base_qty, prev_base_qty
    )
    select
        v_count_id,
        i.product_id,
        i.uom_code,
        i.qty,
        pu.conversion_rate,
        i.qty * pu.conversion_rate,
        coalesce(pr.prev_base_qty, 0)
    from input i
    join product p on p.id = i.product_id and p.is_active
    join product_uom pu
        on pu.product_id = i.product_id and pu.uom_code = i.uom_code
    -- Left join: a product with no history is simply new to this outlet.
    left join previous pr on pr.product_id = i.product_id;

    get diagnostics v_inserted = row_count;

    -- A discontinued product, or a unit the product is not sold in, drops out of
    -- the joins. Storing a partial count would misreport the shelf.
    if v_inserted <> v_expected then
        raise exception
            'stock count %: % of % lines reference an unknown product or unit',
            v_count_id, v_expected - v_inserted, v_expected;
    end if;

    update stock_count set line_count = v_inserted where id = v_count_id;

    insert into visit_step_result (visit_id, form_id, completed_at, payload)
    values (
        v_visit_id,
        'stock_outlet',
        now(),
        jsonb_build_object('stock_count_id', v_count_id, 'line_count', v_inserted)
    )
    on conflict (visit_id, form_id) do update
        set completed_at = excluded.completed_at,
            payload      = excluded.payload;

    return v_count_id;
end;
$$;

revoke execute on function submit_stock_count(jsonb) from public;
grant execute on function submit_stock_count(jsonb) to authenticated;

-- -----------------------------------------------------------------------------
-- Row Level Security
-- -----------------------------------------------------------------------------

alter table stock_count      enable row level security;
alter table stock_count_line enable row level security;

-- Counts are read back across visits, not just the current one: the previous
-- figure the rep compares against belongs to an earlier visit. Scoped to the
-- rep's own branch through the customer rather than to their own counts, because
-- a route can change hands and the shelf history belongs to the outlet.
create policy "rep reads counts for own branch customers"
    on stock_count for select to authenticated
    using (
        exists (
            select 1 from customer c
            where c.id = stock_count.customer_id
              and c.branch_id = current_branch_id()
        )
    );

create policy "rep writes own counts"
    on stock_count for insert to authenticated
    with check (salesperson_id = current_salesperson_id());

-- line_count is written by submit_stock_count in the same transaction.
create policy "rep updates own counts"
    on stock_count for update to authenticated
    using (salesperson_id = current_salesperson_id())
    with check (salesperson_id = current_salesperson_id());

-- A recount deletes the visit's earlier attempt, which may have been made by
-- this rep on this visit. Restricted to their own counts.
create policy "rep deletes own counts"
    on stock_count for delete to authenticated
    using (salesperson_id = current_salesperson_id());

create policy "rep reads count lines in scope"
    on stock_count_line for select to authenticated
    using (
        exists (
            select 1 from stock_count sc
            join customer c on c.id = sc.customer_id
            where sc.id = stock_count_line.stock_count_id
              and c.branch_id = current_branch_id()
        )
    );

create policy "rep writes own count lines"
    on stock_count_line for insert to authenticated
    with check (
        exists (
            select 1 from stock_count sc
            where sc.id = stock_count_line.stock_count_id
              and sc.salesperson_id = current_salesperson_id()
        )
    );

-- Ordering can be gated on counting — the legacy REQUIRE_STOCK_BEFORE_ORDER.
-- It lives in app_setting (seeded, not created here: this file is schema) and is
-- kept global rather than per-step config, as the legacy has it, because it is a
-- policy about how a visit is conducted and a market wanting it wants it
-- everywhere. The client treats an absent setting as false — a setting nobody
-- configured must not stand between a rep and a sale.
