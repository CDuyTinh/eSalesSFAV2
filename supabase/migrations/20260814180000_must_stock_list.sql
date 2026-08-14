-- =============================================================================
-- Must-stock list (MSL)
--
-- Which SKUs are supposed to be on an outlet's shelf, and how much of each.
-- Legacy msl_headers / msl_items.
--
-- This is what the `stock_outlet` step was missing. Counting told the rep what was
-- there; it could not tell them what should have been. With a par level per SKU,
-- the gap between the two becomes a number — the `suggest_qty` the legacy schema
-- stores per count line and that this rebuild deliberately left out until there
-- was something real to aim at.
--
-- Deliberately absent: an msl_results table. The legacy model records MSL
-- compliance per SKU because it comes out of a separate survey flow. Here the
-- stock count already records a quantity per product per visit, so availability
-- and out-of-stock are derived from it. A parallel table would be a second copy
-- of the same fact, kept in sync by hand, and the two would eventually disagree.
-- =============================================================================

create table msl (
    id           uuid    primary key default gen_random_uuid(),
    code         text    not null unique,
    name         text    not null,

    -- Null means "any". A wildcard row is how a national core list is expressed
    -- without enumerating every channel and shop type combination.
    channel_id   uuid    references sales_channel (id),
    shop_type_id uuid    references shop_type (id),

    from_date    date    not null default current_date,
    to_date      date    not null default date '2099-12-31',
    is_active    boolean not null default true,

    constraint msl_dates_ordered check (to_date >= from_date)
);

create index msl_lookup on msl (channel_id, shop_type_id, from_date, to_date);

create table msl_item (
    id           uuid    primary key default gen_random_uuid(),
    msl_id       uuid    not null references msl (id) on delete cascade,
    product_id   uuid    not null references product (id) on delete cascade,

    -- Par level in base units, not sale units. Everything that compares against a
    -- count compares in base units, because the same product gets counted loose
    -- one visit and by the case the next.
    min_base_qty integer not null default 1
        constraint msl_min_base_qty_positive check (min_base_qty > 0),

    unique (msl_id, product_id)
);

create index on msl_item (msl_id);
create index on msl_item (product_id);

comment on table msl is
    'Must-stock list. Several may apply to one outlet; the client takes the union '
    'and the strictest par level, because a list is a set of obligations rather '
    'than a single value to choose between.';

-- -----------------------------------------------------------------------------
-- Row Level Security — reference data, readable by any signed-in rep
-- -----------------------------------------------------------------------------

alter table msl      enable row level security;
alter table msl_item enable row level security;

create policy "reference readable by authenticated"
    on msl for select to authenticated using (true);

create policy "reference readable by authenticated"
    on msl_item for select to authenticated using (true);
