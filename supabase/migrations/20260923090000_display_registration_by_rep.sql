-- =============================================================================
-- Đăng ký chương trình trưng bày
--
-- `display_registration` has existed since the display step was built, and
-- nothing has ever written a row into it: the outlets in the demo data were
-- signed up by the seed. Signing one up is the rep's job — TradeRegis over
-- there, whose InsertTradeRegis writes OM_TDisplayCustomer with Status 'H' and
-- refuses an outlet already in the programme.
--
-- Two things have to exist before that write can be honest.
--
--   A registration window. OM_TDisplay carries RegisFromDate and RegisToDate
--   apart from the dates the display itself runs between: head office closes
--   signups weeks before the programme ends, and a rep who signs an outlet up
--   the day before it finishes has promised something nobody will pay.
--
--   A quota. OM_BudgetTradeSales gives each salesperson so many slots per level
--   (QtyFree), and API_GetTrade_TB_TL_Detail shows what is left of them —
--   QtyFree minus the Portion already spent. Without it the rep can sign up
--   every outlet on the route for the richest level, which is exactly what the
--   quota exists to stop.
--
-- Not ported: BugetID and AllocType. The legacy hangs its quota off a budget
-- document that also funds other trade types, and reproducing that to hold one
-- number per rep per level would be modelling a filing cabinet nobody here has.
-- The slot count lives on the allocation row.
-- =============================================================================

alter table display_program
    /**
     * When a rep may sign an outlet up, which is not the same window the display
     * is audited in. Null on either side means the audit window governs, which is
     * how every programme seeded before this behaved.
     */
    add column if not exists regis_from_date date,
    add column if not exists regis_to_date   date;

comment on column display_program.regis_from_date is
    'OM_TDisplay.RegisFromDate. Null falls back to from_date.';
comment on column display_program.regis_to_date is
    'OM_TDisplay.RegisToDate. Null falls back to to_date.';

/**
 * How many outlets one rep may sign up at one level.
 *
 * OM_BudgetTradeSales, minus the budget document. `used` rises as registrations
 * are filed and is never recomputed from them: a slot released by a rejected
 * signup is head office's to give back, not something a client infers.
 */
create table display_program_quota (
    id             uuid    primary key default gen_random_uuid(),
    level_id       uuid    not null references display_program_level (id) on delete cascade,
    salesperson_id uuid    not null references salesperson (id) on delete cascade,

    slots          integer not null default 0 check (slots >= 0),
    used           integer not null default 0 check (used >= 0),

    created_at     timestamptz not null default now(),
    updated_at     timestamptz not null default now(),

    unique (level_id, salesperson_id),

    -- Spending more than was allocated is a back-office mistake worth refusing at
    -- the table rather than discovering in a reconciliation.
    constraint display_quota_within_slots check (used <= slots)
);

create index on display_program_quota (salesperson_id);

create trigger display_program_quota_set_updated_at
    before update on display_program_quota
    for each row execute function set_updated_at();

alter table display_registration
    /** OM_TDisplayCustomer.SlsperID: who signed this outlet up. */
    add column if not exists salesperson_id uuid references salesperson (id),
    /** The visit it was agreed on, so the conversation can be found again. */
    add column if not exists visit_id uuid references visit (id) on delete set null,
    /** Portion: how many of the rep's slots this signup consumed. */
    add column if not exists portion integer not null default 1 check (portion > 0);

-- -----------------------------------------------------------------------------
-- Row Level Security
-- -----------------------------------------------------------------------------

alter table display_program_quota enable row level security;

-- A rep reads their own allocation and nobody else's: how many slots a colleague
-- has is not their business, and showing it invites the wrong conversation.
create policy "rep reads own display quota" on display_program_quota
    for select to authenticated
    using (salesperson_id = current_salesperson_id());

-- Narrow on purpose: spending a slot is the only change a client may make, and
-- the check constraint above already refuses spending past the allocation.
create policy "rep spends own display quota" on display_program_quota
    for update to authenticated
    using (salesperson_id = current_salesperson_id())
    with check (salesperson_id = current_salesperson_id());

create policy "rep registers an outlet for a display program"
    on display_registration for insert to authenticated
    with check (salesperson_id = current_salesperson_id());
