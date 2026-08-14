-- =============================================================================
-- Product catalogue, pricing and orders
--
-- Backs the `take_order` step of the in-call workflow.
--
-- Two decisions shape everything below.
--
-- Money is integer. Amounts are bigint VND — the currency has no subunit in
-- practice — and VAT is stored in basis points (1000 = 10%) rather than as
-- numeric(7,4). The client has to total an order itself, because an order
-- composed in a shop with no signal sits in the outbox until there is any, and
-- a rep quoting a customer a figure the server later disagrees with is worse
-- than no figure at all. Integer basis points let both sides run the identical
-- arithmetic with no float in it.
--
-- The server prices the order, not the client. The payload carries what the rep
-- chose — product, unit, quantity — and `submit_order` looks up price, VAT and
-- unit conversion itself. A client that could name its own prices would be a
-- client that could book an order at 1 VND. Prices are effective-dated, so an
-- order composed yesterday and delivered from the outbox today still prices at
-- its own order_date.
--
-- Deliberately absent: promotions. The legacy model has promotion_programs,
-- breaks, conditions and an order_promotions audit trail, all driven by an
-- external HQSoft CalDiscountService that is not available to this rebuild.
-- Guessing at those rules would produce orders that disagree with the ERP about
-- money, so discounts are left out entirely rather than approximated. The line
-- and header discount columns exist and stay zero until there is a real engine
-- behind them.
-- =============================================================================

create type order_status as enum (
    'new',          -- submitted by the rep, not yet acknowledged by the ERP
    'confirmed',
    'cancelled'
);

-- -----------------------------------------------------------------------------
-- Units of measure
-- -----------------------------------------------------------------------------

create table uom (
    code text primary key,
    name text not null
);

comment on table uom is
    'Sale and base units, e.g. PCS (le), CASE (thung), PACK (lock).';

-- -----------------------------------------------------------------------------
-- Catalogue — legacy SP_ProductCat / SP_Product
-- -----------------------------------------------------------------------------

create table product_category (
    id         uuid primary key default gen_random_uuid(),
    code       text    not null unique,
    name       text    not null,
    sort_order integer not null default 0,
    is_active  boolean not null default true
);

create table product (
    id          uuid primary key default gen_random_uuid(),
    code        text    not null unique,
    name        text    not null,
    category_id uuid    references product_category (id),
    barcode     text,
    image_url   text,

    -- Smallest unit stock and conversions are expressed in.
    base_uom    text    not null references uom (code),

    -- Basis points: 1000 = 10%. See the header note on integer money.
    vat_basis_points integer not null default 1000
        constraint vat_basis_points_range check (vat_basis_points between 0 and 10000),

    is_active   boolean not null default true,
    created_at  timestamptz not null default now(),
    updated_at  timestamptz not null default now()
);

create index on product (category_id);
create index on product (barcode);

-- The legacy schema's most dangerous table: every wrong total traces back to a
-- conversion. One row per unit the product may be *sold* in, including the base
-- unit itself, so a sale unit always resolves without a special case.
create table product_uom (
    id              uuid    primary key default gen_random_uuid(),
    product_id      uuid    not null references product (id) on delete cascade,
    uom_code        text    not null references uom (code),

    -- Base units in one of this unit. 1 CASE = 24 PCS -> 24. The base unit's
    -- own row is 1.
    conversion_rate integer not null
        constraint conversion_rate_positive check (conversion_rate > 0),

    -- Pre-selected when the rep adds this product to an order.
    is_default_sale boolean not null default false,
    sort_order      integer not null default 0,

    unique (product_id, uom_code)
);

create index on product_uom (product_id);

-- Only one default sale unit per product, or the client would have to pick
-- arbitrarily between them.
create unique index product_uom_one_default
    on product_uom (product_id)
    where is_default_sale;

-- -----------------------------------------------------------------------------
-- Pricing — legacy SP_PriceList
--
-- The legacy model prices by a dedicated price_group dimension. Customers here
-- already carry `class_id`, which is what that grouping means in this data, so
-- pricing hangs off customer_class instead of adding a parallel table. A null
-- class_id is the list price every customer falls back to, which keeps a
-- product priceable without a row per class.
-- -----------------------------------------------------------------------------

create table price_list (
    id         uuid   primary key default gen_random_uuid(),
    product_id uuid   not null references product (id) on delete cascade,
    uom_code   text   not null references uom (code),
    class_id   uuid   references customer_class (id),
    price      bigint not null constraint price_not_negative check (price >= 0),
    from_date  date   not null default current_date,
    to_date    date   not null default date '2099-12-31',

    constraint price_dates_ordered check (to_date >= from_date)
);

-- Matches the lookup submit_order and the catalogue pull both run.
create index price_list_lookup
    on price_list (product_id, uom_code, class_id, from_date, to_date);

-- One price per product/unit/class per period. Partial indexes because a null
-- class_id would otherwise never collide with itself.
create unique index price_list_unique_class
    on price_list (product_id, uom_code, class_id, from_date)
    where class_id is not null;

create unique index price_list_unique_default
    on price_list (product_id, uom_code, from_date)
    where class_id is null;

-- -----------------------------------------------------------------------------
-- Orders — legacy OM_Order / OM_OrderDet
-- -----------------------------------------------------------------------------

create table sales_order (
    -- Supplied by the client, not defaulted here: it is the idempotency key
    -- that makes an outbox replay a no-op rather than a duplicate order.
    id                uuid primary key,

    order_no          text         not null unique,
    customer_id       uuid         not null references customer (id),
    salesperson_id    uuid         not null references salesperson (id),
    branch_id         uuid         not null references branch (id),
    visit_id          uuid         not null references visit (id) on delete cascade,

    order_date        date         not null,
    status            order_status not null default 'new',

    sub_total         bigint       not null default 0,   -- before discount
    discount_amount   bigint       not null default 0,   -- always 0: no engine yet
    vat_amount        bigint       not null default 0,
    total_amount      bigint       not null default 0,

    note              text,

    -- What the device totalled before sending. Recorded, not enforced: the
    -- server's figure above is authoritative, but the rep quoted this one to the
    -- customer, so a divergence is a bug worth being able to find with a query
    -- instead of a field report. Raising here would strand a real order over an
    -- arithmetic disagreement, which is the worse failure of the two.
    client_total_amount bigint     not null default 0,

    -- When the rep pressed submit, which is not when the row arrived. The gap
    -- is however long the outbox waited for signal.
    client_created_at timestamptz  not null,
    created_at        timestamptz  not null default now(),
    updated_at        timestamptz  not null default now()
);

create index on sales_order (salesperson_id, order_date desc);
create index on sales_order (customer_id, order_date desc);
create index on sales_order (visit_id);

create table sales_order_line (
    id               uuid   primary key default gen_random_uuid(),
    order_id         uuid   not null references sales_order (id) on delete cascade,
    line_no          integer not null,

    product_id       uuid   not null references product (id),
    uom_code         text   not null references uom (code),
    qty              integer not null constraint qty_positive check (qty > 0),

    -- Snapshots. The catalogue may be re-priced or re-packed tomorrow; what the
    -- customer agreed to today must not move with it.
    conversion_rate  integer not null,
    base_qty         integer not null,
    price            bigint not null,
    vat_basis_points integer not null,

    gross_amount     bigint not null,   -- qty x price
    discount_amount  bigint not null default 0,
    vat_amount       bigint not null,
    line_amount      bigint not null,   -- gross - discount + vat

    unique (order_id, line_no),
    -- One line per product/unit: two lines for the same thing is a client bug,
    -- and it would make the order total depend on how the rep happened to tap.
    unique (order_id, product_id, uom_code)
);

create index on sales_order_line (order_id);
create index on sales_order_line (product_id);

create trigger sales_order_set_updated_at
    before update on sales_order
    for each row execute function set_updated_at();

create trigger product_set_updated_at
    before update on product
    for each row execute function set_updated_at();

-- -----------------------------------------------------------------------------
-- submit_order
--
-- One transaction for the header, the lines and the workflow step, so a
-- delivered order can never leave the rep looking like they still owe the step
-- — or the reverse.
--
-- security invoker: every insert below still passes through the policies at the
-- bottom of this file. The function exists to make the write atomic and to keep
-- pricing on this side of the wire, not to escape RLS.
-- -----------------------------------------------------------------------------

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

    update sales_order o
    set sub_total    = t.sub_total,
        vat_amount   = t.vat_amount,
        total_amount = t.total_amount
    from (
        select
            sum(gross_amount) as sub_total,
            sum(vat_amount)   as vat_amount,
            sum(line_amount)  as total_amount
        from sales_order_line
        where order_id = v_order_id
    ) t
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

-- -----------------------------------------------------------------------------
-- Row Level Security
-- -----------------------------------------------------------------------------

alter table uom               enable row level security;
alter table product_category  enable row level security;
alter table product           enable row level security;
alter table product_uom       enable row level security;
alter table price_list        enable row level security;
alter table sales_order       enable row level security;
alter table sales_order_line  enable row level security;

-- Catalogue is reference data: every rep reads it, no rep writes it.
create policy "reference readable by authenticated"
    on uom for select to authenticated using (true);
create policy "reference readable by authenticated"
    on product_category for select to authenticated using (true);
create policy "reference readable by authenticated"
    on product for select to authenticated using (true);
create policy "reference readable by authenticated"
    on product_uom for select to authenticated using (true);

-- Prices a rep may see are their own customers' prices and the list price. A
-- rep has no business reading what another customer class pays.
create policy "rep reads prices for own customer classes"
    on price_list for select to authenticated
    using (
        class_id is null
        or exists (
            select 1 from customer c
            where c.class_id = price_list.class_id
              and c.branch_id = current_branch_id()
        )
    );

create policy "rep reads own orders"
    on sales_order for select to authenticated
    using (salesperson_id = current_salesperson_id());

create policy "rep writes own orders"
    on sales_order for insert to authenticated
    with check (salesperson_id = current_salesperson_id());

-- Totals are written by submit_order in the same transaction as the insert.
create policy "rep updates own orders"
    on sales_order for update to authenticated
    using (salesperson_id = current_salesperson_id())
    with check (salesperson_id = current_salesperson_id());

-- Scoped through the parent order; no salesperson column to keep in sync.
create policy "rep reads own order lines"
    on sales_order_line for select to authenticated
    using (
        exists (
            select 1 from sales_order o
            where o.id = sales_order_line.order_id
              and o.salesperson_id = current_salesperson_id()
        )
    );

create policy "rep writes own order lines"
    on sales_order_line for insert to authenticated
    with check (
        exists (
            select 1 from sales_order o
            where o.id = sales_order_line.order_id
              and o.salesperson_id = current_salesperson_id()
        )
    );
