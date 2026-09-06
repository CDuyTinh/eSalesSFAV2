-- =============================================================================
-- Khuyến mãi tay
--
-- Everything so far has been automatic: the basket qualifies, the rule fires,
-- nobody decides. A manual promotion is the other half — a catalogue of
-- discounts head office has approved in advance and left the rep to apply by
-- judgement. Closing a difficult shop, a goodwill gesture on a late delivery,
-- an agreed favour: things a rule cannot see from the basket.
--
--   OM_DiscDescr         -> manual_promotion       the catalogue entry
--   OM_DiscDescrFreeItem -> manual_promotion_item  goods it gives away
--   OM_DiscDescCpny      -> manual_promotion.branch_id
--   OM_PDAOrdManualDisc  -> sales_order_manual_discount
--
-- Approved in advance, applied at will. There is no per-use approval anywhere in
-- the legacy — `Status = 'C'` is on the catalogue entry, not the usage — so the
-- control is which entries exist and who can see them, not a workflow after the
-- fact. `AllowEdit` is the one place the rep may change a number, and it is per
-- entry: some are a fixed 50.000 đ, some are "up to", and the catalogue says
-- which.
--
-- Deliberately not merged with `sales_order_discount`. That table's rows point
-- at a rule and a level and can be recomputed from the basket; these cannot be
-- recomputed from anything, because a person decided them. Keeping them apart is
-- what lets the order say which part of its discount was earned and which part
-- was given.
-- =============================================================================

-- OM_DiscDescr.PromoType.
create type manual_promotion_type as enum (
    'percent',    -- P: Discount is a percentage of the order
    'amount',     -- A and the rest: Discount is money
    'free_item'   -- I: goods, and Discount is not read
);

create table manual_promotion (
    id         uuid    primary key default gen_random_uuid(),
    code       text    not null unique,
    name       text    not null,

    promo_type manual_promotion_type not null,

    /**
     * Percent when the type is percent, dong when it is amount, ignored for
     * goods. One column because the legacy has one column, and splitting it
     * would invent a distinction the catalogue does not make.
     */
    value      numeric(18, 4) not null default 0 check (value >= 0),

    /**
     * AllowEdit. False means the entry is a fixed amount the rep applies or does
     * not; true means the catalogue value is a ceiling they may come under.
     */
    allow_edit boolean not null default false,

    from_date  date    not null,
    to_date    date    not null,

    /** Null makes it available in every branch. */
    branch_id  uuid    references branch (id),

    is_active  boolean not null default true,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),

    constraint manual_promotion_dates check (to_date >= from_date)
);

create trigger manual_promotion_set_updated_at
    before update on manual_promotion
    for each row execute function set_updated_at();

-- The goods a manual promotion gives. Several rows means several items, all of
-- them given: unlike the automatic side there is no alternatives list here,
-- because the rep is already choosing at the level above.
create table manual_promotion_item (
    id           uuid    primary key default gen_random_uuid(),
    promotion_id uuid    not null references manual_promotion (id) on delete cascade,
    product_id   uuid    not null references product (id),
    uom_code     text    not null references uom (code),
    qty          integer not null check (qty > 0),
    sort_order   integer not null default 0,

    unique (promotion_id, product_id, uom_code)
);

create index on manual_promotion_item (promotion_id);

-- -----------------------------------------------------------------------------
-- What a rep gave away on one order
-- -----------------------------------------------------------------------------

create table sales_order_manual_discount (
    id              uuid    primary key default gen_random_uuid(),
    order_id        uuid    not null references sales_order (id) on delete cascade,
    promotion_id    uuid    not null references manual_promotion (id),

    promo_type      manual_promotion_type not null,

    /** Money off, already worked out from the percentage where that applies. */
    discount_amount bigint  not null default 0 check (discount_amount >= 0),
    percent         numeric(7, 4),

    -- Denormalised so a statement of what was given survives the catalogue entry
    -- being edited or retired next month.
    descr           text    not null default '',

    created_at      timestamptz not null default now(),

    -- One application of an entry per order. Applying it twice is not a thing
    -- the legacy screen can express either.
    unique (order_id, promotion_id)
);

create index on sales_order_manual_discount (order_id);

create table sales_order_manual_discount_item (
    id                 uuid    primary key default gen_random_uuid(),
    manual_discount_id uuid    not null references sales_order_manual_discount (id)
                               on delete cascade,
    product_id         uuid    not null references product (id),
    uom_code           text    not null references uom (code),
    qty                integer not null check (qty > 0),

    unique (manual_discount_id, product_id, uom_code)
);

create index on sales_order_manual_discount_item (manual_discount_id);

-- -----------------------------------------------------------------------------
-- Row Level Security
-- -----------------------------------------------------------------------------

alter table manual_promotion                  enable row level security;
alter table manual_promotion_item             enable row level security;
alter table sales_order_manual_discount       enable row level security;
alter table sales_order_manual_discount_item  enable row level security;

create policy "everyone reads manual promotions" on manual_promotion
    for select to authenticated using (true);

create policy "everyone reads manual promotion items" on manual_promotion_item
    for select to authenticated using (true);

create policy "rep reads own manual discounts"
    on sales_order_manual_discount for select to authenticated
    using (
        exists (
            select 1 from sales_order o
            where o.id = sales_order_manual_discount.order_id
              and o.salesperson_id = current_salesperson_id()
        )
    );

create policy "rep writes own manual discounts"
    on sales_order_manual_discount for insert to authenticated
    with check (
        exists (
            select 1 from sales_order o
            where o.id = sales_order_manual_discount.order_id
              and o.salesperson_id = current_salesperson_id()
        )
    );

create policy "rep reads own manual discount items"
    on sales_order_manual_discount_item for select to authenticated
    using (
        exists (
            select 1 from sales_order_manual_discount d
            join sales_order o on o.id = d.order_id
            where d.id = sales_order_manual_discount_item.manual_discount_id
              and o.salesperson_id = current_salesperson_id()
        )
    );

create policy "rep writes own manual discount items"
    on sales_order_manual_discount_item for insert to authenticated
    with check (
        exists (
            select 1 from sales_order_manual_discount d
            join sales_order o on o.id = d.order_id
            where d.id = sales_order_manual_discount_item.manual_discount_id
              and o.salesperson_id = current_salesperson_id()
        )
    );

-- -----------------------------------------------------------------------------
-- The catalogue this rep may apply today
--
-- API_GetManualPromo, minus the site plumbing. The legacy resolves a warehouse
-- from the rep's default and zeroes the giveable quantity where that warehouse
-- is empty; that stock check belongs with the automatic side's, which is not
-- ported yet, so it is left out of both rather than done in one place only.
-- -----------------------------------------------------------------------------

create or replace function manual_promotions_for(p_customer_id uuid)
returns table (
    promotion_id uuid,
    code         text,
    name         text,
    promo_type   manual_promotion_type,
    value        numeric,
    allow_edit   boolean,
    from_date    date,
    to_date      date,
    items        jsonb
)
language sql
stable
security invoker
set search_path = public, pg_temp
as $$
    with me as (
        select branch_id from customer where id = p_customer_id
    )
    select
        m.id, m.code, m.name, m.promo_type, m.value, m.allow_edit,
        m.from_date, m.to_date,
        coalesce(
            (
                select jsonb_agg(
                    jsonb_build_object(
                        'product_id', mi.product_id,
                        'product_code', p.code,
                        'product_name', p.name,
                        'uom_code', mi.uom_code,
                        'uom_name', u.name,
                        'qty', mi.qty
                    )
                    order by mi.sort_order, p.name
                )
                from manual_promotion_item mi
                join product p on p.id = mi.product_id
                join uom u on u.code = mi.uom_code
                where mi.promotion_id = m.id
            ),
            '[]'::jsonb
        ) as items
    from manual_promotion m
    cross join me
    where m.is_active
      and current_date between m.from_date and m.to_date
      and (m.branch_id is null or m.branch_id = me.branch_id)
    -- Goods last: a rep scanning the list is usually looking for money, and the
    -- two read differently enough that mixing them by name helps nobody.
    order by m.promo_type, m.name;
$$;

comment on function manual_promotions_for(uuid) is
    'Manual discounts this rep may apply at this outlet today. The legacy '
    'API_GetManualPromo.';
