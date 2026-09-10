-- =============================================================================
-- Chương trình tích lũy
--
-- The second of the two trade types a rep signs an outlet up for. Display was
-- the first; this is TradeType 'A', and it is the last family the customer
-- screen's programme tab was missing.
--
--   OM_Accumulated            -> loyalty_program        the programme and its windows
--   OM_AccumulatedLevel       -> loyalty_program_level  LevelFrom..LevelTo, PercentBonus
--   OM_AccumulatedInvtSetup   -> loyalty_program_item   which products count
--   OM_AccumulatedRegis       -> loyalty_registration   who signed the outlet up
--   OM_BudgetTradeSales 'A'   -> loyalty_program_quota  slots per rep per level
--
-- The shape is the display programme's, which is not a coincidence: over there
-- one proc serves both (API_GetTrade_TB_TL_Detail, TB and TL) and one command
-- registers into either. The difference is what a level measures. A display
-- level counts facings on a shelf; a loyalty level is a band of trade -- buy
-- between so much and so much over the programme and earn a percentage back.
--
-- One deliberate divergence, and it is the interesting one. The legacy's detail
-- proc returns `Actual = 0`: the SFA never computes how far an outlet has got,
-- it reads OM_AccumulatedPoints, a figure the back office maintains. Porting
-- that faithfully would mean a progress table nothing in this app ever writes,
-- which is the same dead row this rebuild has already refused twice. So progress
-- is derived here from the orders themselves -- the counted products, inside the
-- programme's window, on orders that were not cancelled. It is self-maintaining,
-- it agrees with the money by construction, and it makes the tab worth opening
-- rather than showing a rep a zero.
--
-- Not ported: Point and LevelRewardType's goods variant. A programme that pays
-- in points redeemable against a catalogue, or in free cases rather than a
-- percentage, needs a redemption flow nobody has asked for yet; `counts_by` and
-- `reward_percent` cover the shape the demo data actually uses, and a programme
-- of the other kind is better refused loudly later than modelled wrongly now.
-- =============================================================================

create type loyalty_counts_by as enum (
    'amount',   -- dong of counted product bought
    'quantity'  -- base units of it
);

create table loyalty_program (
    id            uuid    primary key default gen_random_uuid(),
    code          text    not null unique,
    name          text    not null,

    /** What the levels are bands of. */
    counts_by     loyalty_counts_by not null default 'amount',

    -- The window trade accumulates in.
    from_date     date    not null,
    to_date       date    not null,

    -- When a rep may sign an outlet up, which closes earlier. Null on either
    -- side falls back to the accumulation window.
    regis_from_date date,
    regis_to_date   date,

    /** Null runs the programme in every branch. */
    branch_id     uuid    references branch (id),

    specification text,

    is_active     boolean not null default true,
    created_at    timestamptz not null default now(),
    updated_at    timestamptz not null default now(),

    constraint loyalty_program_dates check (to_date >= from_date)
);

create trigger loyalty_program_set_updated_at
    before update on loyalty_program
    for each row execute function set_updated_at();

/**
 * One band of trade and what it pays.
 *
 * `target_to` null is the open top band — LevelTo on the richest level, which is
 * how a programme says "and everything above this".
 */
create table loyalty_program_level (
    id             uuid    primary key default gen_random_uuid(),
    program_id     uuid    not null references loyalty_program (id) on delete cascade,
    code           text    not null,
    name           text    not null,

    target_from    numeric(18, 2) not null check (target_from >= 0),
    target_to      numeric(18, 2),

    /** PercentBonus, in basis points so a half percent is expressible. */
    reward_basis_points integer not null default 0 check (reward_basis_points >= 0),

    sort_order     integer not null default 0,

    unique (program_id, code),
    constraint loyalty_level_band check (target_to is null or target_to > target_from)
);

create index on loyalty_program_level (program_id);

/** Which products count towards the band. Nothing else the outlet buys does. */
create table loyalty_program_item (
    id         uuid    primary key default gen_random_uuid(),
    program_id uuid    not null references loyalty_program (id) on delete cascade,
    product_id uuid    not null references product (id),

    unique (program_id, product_id)
);

create index on loyalty_program_item (program_id);

create table loyalty_registration (
    id             uuid    primary key default gen_random_uuid(),
    program_id     uuid    not null references loyalty_program (id) on delete cascade,
    level_id       uuid    not null references loyalty_program_level (id),
    customer_id    uuid    not null references customer (id) on delete cascade,

    -- H / C / D, as the display registration reads them. A pending signup still
    -- accumulates: the outlet is buying while head office rules on the paperwork.
    status         text    not null default 'pending'
                   check (status in ('pending', 'approved', 'rejected')),

    salesperson_id uuid    references salesperson (id),
    visit_id       uuid    references visit (id) on delete set null,
    /** Portion: how many of the rep's slots this signup consumed. */
    portion        integer not null default 1 check (portion > 0),
    reason         text,

    registered_at  date    not null default current_date,
    created_at     timestamptz not null default now(),
    updated_at     timestamptz not null default now(),

    unique (program_id, customer_id)
);

create index on loyalty_registration (customer_id);

create trigger loyalty_registration_set_updated_at
    before update on loyalty_registration
    for each row execute function set_updated_at();

create table loyalty_program_quota (
    id             uuid    primary key default gen_random_uuid(),
    level_id       uuid    not null references loyalty_program_level (id) on delete cascade,
    salesperson_id uuid    not null references salesperson (id) on delete cascade,

    slots          integer not null default 0 check (slots >= 0),
    used           integer not null default 0 check (used >= 0),

    created_at     timestamptz not null default now(),
    updated_at     timestamptz not null default now(),

    unique (level_id, salesperson_id),
    constraint loyalty_quota_within_slots check (used <= slots)
);

create index on loyalty_program_quota (salesperson_id);

create trigger loyalty_program_quota_set_updated_at
    before update on loyalty_program_quota
    for each row execute function set_updated_at();

-- -----------------------------------------------------------------------------
-- Row Level Security
-- -----------------------------------------------------------------------------

alter table loyalty_program       enable row level security;
alter table loyalty_program_level enable row level security;
alter table loyalty_program_item  enable row level security;
alter table loyalty_registration  enable row level security;
alter table loyalty_program_quota enable row level security;

create policy "everyone reads loyalty programs" on loyalty_program
    for select to authenticated using (true);
create policy "everyone reads loyalty levels" on loyalty_program_level
    for select to authenticated using (true);
create policy "everyone reads loyalty items" on loyalty_program_item
    for select to authenticated using (true);
create policy "everyone reads loyalty registrations" on loyalty_registration
    for select to authenticated using (true);

create policy "rep registers an outlet for a loyalty program"
    on loyalty_registration for insert to authenticated
    with check (salesperson_id = current_salesperson_id());

-- A rep sees their own allocation and nobody else's, and may only spend it.
create policy "rep reads own loyalty quota" on loyalty_program_quota
    for select to authenticated
    using (salesperson_id = current_salesperson_id());

create policy "rep spends own loyalty quota" on loyalty_program_quota
    for update to authenticated
    using (salesperson_id = current_salesperson_id())
    with check (salesperson_id = current_salesperson_id());
