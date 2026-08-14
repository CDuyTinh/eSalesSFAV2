-- =============================================================================
-- eSales SFA v2 — core schema
--
-- Modelled on the legacy HQSoft eSales SFA SQL Server database, but redesigned
-- rather than ported: snake_case, surrogate uuid keys alongside the business
-- codes the ERP world actually uses, and timestamptz everywhere.
--
-- Scope of this migration: everything the Login -> Route -> Check-in slice
-- needs, plus the config tables the client caches locally (Room).
-- Ordering, stock and promotion land in later migrations.
-- =============================================================================

create extension if not exists "pgcrypto";

-- -----------------------------------------------------------------------------
-- Enums
-- -----------------------------------------------------------------------------

create type visit_status as enum (
    'planned',      -- on today's route, not started
    'in_progress',  -- checked in, not yet checked out
    'completed',    -- checked in and out
    'no_order',     -- visited but customer did not buy
    'closed'        -- outlet closed at time of visit
);

create type timekeeping_type as enum ('check_in', 'check_out');

create type reason_kind as enum (
    'no_order',           -- customer declined to order
    'outlet_closed',
    'gps_out_of_range',   -- legacy 'DS'
    'gps_low_accuracy',   -- legacy 'OA'
    'gps_unavailable',    -- legacy 'KP'
    'photo_skipped'       -- legacy 'CD'
);

-- -----------------------------------------------------------------------------
-- Organisation & identity
-- -----------------------------------------------------------------------------

create table branch (
    id          uuid primary key default gen_random_uuid(),
    code        text        not null unique,
    name        text        not null,
    address     text,
    lat         double precision,
    lng         double precision,
    is_active   boolean     not null default true,
    created_at  timestamptz not null default now()
);

comment on table branch is 'Distributor / depot. Legacy PPC_Branch.';

-- One row per field sales rep. Linked 1:1 to a Supabase Auth user.
-- Login is by `code` (username); the client maps code -> synthetic email
-- before calling GoTrue, since Supabase Auth is email-based.
create table salesperson (
    id                  uuid primary key default gen_random_uuid(),
    user_id             uuid unique references auth.users (id) on delete set null,
    code                text        not null unique,
    full_name           text        not null,
    branch_id           uuid        not null references branch (id),
    phone               text,
    -- Device binding: the legacy app refuses login from an unknown IMEI unless
    -- the account is explicitly allowed on multiple devices.
    device_id           text,
    allow_multi_device  boolean     not null default false,
    is_active           boolean     not null default true,
    created_at          timestamptz not null default now(),
    updated_at          timestamptz not null default now()
);

create index on salesperson (branch_id);

-- -----------------------------------------------------------------------------
-- Geography — legacy SI_State / SI_District / SI_Ward
-- -----------------------------------------------------------------------------

create table province (
    id      uuid primary key default gen_random_uuid(),
    code    text not null unique,
    name    text not null
);

create table district (
    id          uuid primary key default gen_random_uuid(),
    province_id uuid not null references province (id) on delete cascade,
    code        text not null unique,
    name        text not null
);
create index on district (province_id);

create table ward (
    id          uuid primary key default gen_random_uuid(),
    district_id uuid not null references district (id) on delete cascade,
    code        text not null unique,
    name        text not null
);
create index on ward (district_id);

-- -----------------------------------------------------------------------------
-- Customer classification — legacy AR_CustClass / AR_Channel / AR_ShopType
-- -----------------------------------------------------------------------------

create table customer_class (
    id   uuid primary key default gen_random_uuid(),
    code text not null unique,
    name text not null
);

create table sales_channel (
    id   uuid primary key default gen_random_uuid(),
    code text not null unique,
    name text not null
);

create table shop_type (
    id   uuid primary key default gen_random_uuid(),
    code text not null unique,
    name text not null
);

-- -----------------------------------------------------------------------------
-- Customer (outlet) — legacy AR_Customer
-- -----------------------------------------------------------------------------

create table customer (
    id                uuid primary key default gen_random_uuid(),
    code              text        not null,
    name              text        not null,
    branch_id         uuid        not null references branch (id),
    phone             text,
    address           text,
    ward_id           uuid references ward (id),
    lat               double precision,
    lng               double precision,
    avatar_url        text,
    class_id          uuid references customer_class (id),
    channel_id        uuid references sales_channel (id),
    shop_type_id      uuid references shop_type (id),
    -- Radius (metres) the rep must be within to check in. Null = use the
    -- global app_setting default.
    checkin_radius_m  integer,
    is_active         boolean     not null default true,
    created_at        timestamptz not null default now(),
    updated_at        timestamptz not null default now(),
    -- Customer codes are unique per branch, not globally, in the legacy model.
    unique (branch_id, code)
);

create index on customer (branch_id);
create index on customer (ward_id);

-- -----------------------------------------------------------------------------
-- Sales route — legacy OM_SalesRoute / OM_SalesRouteDet
-- -----------------------------------------------------------------------------

create table sales_route (
    id              uuid primary key default gen_random_uuid(),
    code            text        not null unique,
    name            text        not null,
    branch_id       uuid        not null references branch (id),
    salesperson_id  uuid        not null references salesperson (id),
    is_active       boolean     not null default true,
    created_at      timestamptz not null default now()
);

create index on sales_route (salesperson_id);

-- Which customers sit on a route, in what order, and on which days.
-- visit_weekdays uses ISO numbering (1 = Monday .. 7 = Sunday).
-- visit_weeks encodes the 4-week MCL cycle from the legacy PPC_WeekofVisitMCL;
-- an empty array means "every week".
create table route_customer (
    id              uuid primary key default gen_random_uuid(),
    sales_route_id  uuid       not null references sales_route (id) on delete cascade,
    customer_id     uuid       not null references customer (id) on delete cascade,
    visit_order     integer    not null default 0,
    visit_weekdays  smallint[] not null default '{}',
    visit_weeks     smallint[] not null default '{}',
    is_active       boolean    not null default true,
    unique (sales_route_id, customer_id),
    constraint visit_weekdays_valid
        check (visit_weekdays <@ array[1,2,3,4,5,6,7]::smallint[]),
    constraint visit_weeks_valid
        check (visit_weeks <@ array[1,2,3,4]::smallint[])
);

create index on route_customer (sales_route_id);
create index on route_customer (customer_id);

-- -----------------------------------------------------------------------------
-- Reason codes — legacy PPC_ReasonCode
-- -----------------------------------------------------------------------------

create table reason_code (
    id         uuid primary key default gen_random_uuid(),
    code       text        not null unique,
    name       text        not null,
    kind       reason_kind not null,
    is_active  boolean     not null default true
);

create index on reason_code (kind);

-- -----------------------------------------------------------------------------
-- Visit — legacy PPC_OutsideChecking + PPC_SalesTrace
--
-- One row per (customer, salesperson, day). Check-in and check-out are two
-- updates to the same row, which is what makes duration and "still in call"
-- trivially queryable.
-- -----------------------------------------------------------------------------

create table visit (
    id                    uuid primary key default gen_random_uuid(),
    customer_id           uuid         not null references customer (id),
    salesperson_id        uuid         not null references salesperson (id),
    branch_id             uuid         not null references branch (id),
    visit_date            date         not null,
    status                visit_status not null default 'planned',

    check_in_at           timestamptz,
    check_in_lat          double precision,
    check_in_lng          double precision,
    check_in_accuracy_m   double precision,
    check_in_distance_m   double precision,
    check_in_photo_path   text,
    check_in_reason_id    uuid references reason_code (id),

    check_out_at          timestamptz,
    check_out_lat         double precision,
    check_out_lng         double precision,
    check_out_photo_path  text,

    no_order_reason_id    uuid references reason_code (id),
    note                  text,

    -- Captured for fraud and "why did this check-in fail" analysis, as the
    -- legacy app did.
    network_type          text,
    battery_level         smallint,

    created_at            timestamptz not null default now(),
    updated_at            timestamptz not null default now(),

    unique (customer_id, salesperson_id, visit_date),
    constraint check_out_after_check_in
        check (check_out_at is null or check_in_at is null or check_out_at >= check_in_at),
    constraint battery_level_range
        check (battery_level is null or battery_level between 0 and 100)
);

create index on visit (salesperson_id, visit_date desc);
create index on visit (customer_id, visit_date desc);
create index on visit (branch_id, visit_date desc);

-- -----------------------------------------------------------------------------
-- Timekeeping — legacy PPC_TimeKeeping (branch-level clock in/out)
-- -----------------------------------------------------------------------------

create table timekeeping (
    id              uuid primary key default gen_random_uuid(),
    salesperson_id  uuid             not null references salesperson (id),
    branch_id       uuid             not null references branch (id),
    type            timekeeping_type not null,
    happened_at     timestamptz      not null default now(),
    lat             double precision,
    lng             double precision,
    accuracy_m      double precision,
    distance_m      double precision,
    photo_path      text,
    reason_id       uuid references reason_code (id),
    created_at      timestamptz      not null default now()
);

create index on timekeeping (salesperson_id, happened_at desc);

-- -----------------------------------------------------------------------------
-- Client-driven configuration
-- -----------------------------------------------------------------------------

-- Legacy PPC_SalesStep: the in-call workflow is data, not code. The client
-- renders whatever steps this table returns, in `step` order.
create table sales_step (
    id           uuid primary key default gen_random_uuid(),
    form_id      text        not null unique,
    step         integer     not null,
    title_key    text        not null,
    is_required  boolean     not null default false,
    needs_visit  boolean     not null default true,
    config       jsonb       not null default '{}'::jsonb,
    is_active    boolean     not null default true
);

comment on column sales_step.form_id is
    'Stable identifier the client switches on, e.g. take_order, stock_outlet.';

-- Legacy PPC_Setting: scalar knobs (GPS radius, late thresholds, toggles).
create table app_setting (
    id          uuid primary key default gen_random_uuid(),
    key         text        not null unique,
    value       text        not null,
    description text
);

-- Legacy PPC_Language: every UI label is served from here, not from strings.xml.
create table translation (
    id         uuid primary key default gen_random_uuid(),
    lang_code  text not null,
    key        text not null,
    value      text not null,
    unique (lang_code, key)
);

create index on translation (lang_code);

-- -----------------------------------------------------------------------------
-- updated_at maintenance
-- -----------------------------------------------------------------------------

create or replace function set_updated_at()
returns trigger
language plpgsql
as $$
begin
    new.updated_at = now();
    return new;
end;
$$;

create trigger salesperson_set_updated_at
    before update on salesperson
    for each row execute function set_updated_at();

create trigger customer_set_updated_at
    before update on customer
    for each row execute function set_updated_at();

create trigger visit_set_updated_at
    before update on visit
    for each row execute function set_updated_at();

-- -----------------------------------------------------------------------------
-- Storage bucket for visit photos (check-in / check-out evidence)
-- -----------------------------------------------------------------------------

insert into storage.buckets (id, name, public)
values ('visit-photos', 'visit-photos', false)
on conflict (id) do nothing;
