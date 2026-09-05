-- =============================================================================
-- Chương trình trưng bày
--
-- `display_remark` photographed a shelf. The step it replaces audits a
-- commitment: head office runs display programmes, an outlet signs up to one at
-- a level, the level names how many facings that level is worth, and the rep's
-- job at the shelf is to count the facings actually there and say whether the
-- outlet earned it.
--
-- The legacy shape, and what each piece becomes here:
--
--   OM_TDisplay          -> display_program          the programme and its dates
--   OM_TDisplayLevel     -> display_program_level    NumSurface, Bonus per level
--   OM_TDisplayCustomer  -> display_registration     which outlet, at which level
--   OM_TDisplayCpny      -> display_program.branch_id  which branch it runs in
--   PPC_DisplayRemark    -> display_audit (extended) FaceRemark and Evaluate
--
-- OM_TDisplayCpny is a six-column key over branch route, zone, territory, state
-- and district. That is a targeting language, and inventing five levels of it
-- for a schema with one branch table would be modelling a hierarchy nobody here
-- has yet. One branch, nullable for a programme that runs everywhere.
--
-- `requires_registration` is CheckRegistry, and it matters: a programme with it
-- off applies to every outlet on the route at the programme's lowest level,
-- with no registration row at all. Both shapes are real in the legacy data and
-- the listing below handles both.
-- =============================================================================

create table display_program (
    id          uuid        primary key default gen_random_uuid(),
    code        text        not null unique,
    name        text        not null,

    -- The window the programme is audited in. A rep visiting outside it sees
    -- nothing, which is how a finished programme stops appearing without anyone
    -- deleting it.
    from_date   date        not null,
    to_date     date        not null,

    /**
     * Null runs the programme in every branch — the legacy's "no OM_TDisplayCpny
     * row narrower than the branch route" case.
     */
    branch_id   uuid        references branch (id),

    -- CheckRegistry. Off means every outlet on the route is in it, at the
    -- lowest level; on means only the outlets with a registration row.
    requires_registration boolean not null default true,

    /** Specification — what the display has to look like, in words. */
    specification text,

    is_active   boolean     not null default true,
    created_at  timestamptz not null default now(),
    updated_at  timestamptz not null default now(),

    constraint display_program_dates check (to_date >= from_date)
);

create trigger display_program_set_updated_at
    before update on display_program
    for each row execute function set_updated_at();

create table display_program_level (
    id             uuid    primary key default gen_random_uuid(),
    program_id     uuid    not null references display_program (id) on delete cascade,
    code           text    not null,
    name           text    not null,

    /**
     * NumSurface: facings this level is worth. The number the rep counts against,
     * and the whole reason the step is called chấm rather than chụp.
     */
    required_faces integer not null check (required_faces > 0),

    /** Bonus, in dong. Zero for a programme that pays in something else. */
    bonus_amount   bigint  not null default 0 check (bonus_amount >= 0),

    sort_order     integer not null default 0,

    unique (program_id, code)
);

create table display_registration (
    id             uuid        primary key default gen_random_uuid(),
    program_id     uuid        not null references display_program (id) on delete cascade,
    level_id       uuid        not null references display_program_level (id),
    customer_id    uuid        not null references customer (id) on delete cascade,

    -- The legacy's C / H / W. Pending registrations are audited too: the outlet
    -- has built the display and is waiting on head office, and refusing to score
    -- it until the paperwork lands is how a rep gets blamed for the paperwork.
    status         text        not null default 'approved'
                   check (status in ('pending', 'approved', 'rejected')),

    registered_at  date        not null default current_date,
    created_at     timestamptz not null default now(),

    -- One registration per outlet per programme; changing level updates the row.
    unique (program_id, customer_id)
);

create index on display_registration (customer_id);

-- -----------------------------------------------------------------------------
-- The audit becomes per-programme
-- -----------------------------------------------------------------------------

alter table display_audit
    add column program_id    uuid references display_program (id),
    add column level_id      uuid references display_program_level (id),
    /** FaceRemark: what the rep counted on the shelf. */
    add column counted_faces integer check (counted_faces >= 0),
    /** Evaluate: the rep's own yes or no, which is not derived from the count. */
    add column achieved      boolean;

-- One audit per programme per visit, replacing one audit per visit. The old
-- constraint would have allowed a rep to score exactly one of an outlet's three
-- programmes.
alter table display_audit drop constraint display_audit_visit_id_key;

create unique index display_audit_one_per_program
    on display_audit (visit_id, program_id)
    where program_id is not null;

-- A market with no programmes still gets the plain photo audit, and still gets
-- only one of it. Null program_id is not covered by the index above, because
-- nulls are distinct to a unique index.
create unique index display_audit_one_plain_per_visit
    on display_audit (visit_id)
    where program_id is null;

-- The rep's own count is only meaningful next to a level's target, so the two
-- travel together or not at all.
alter table display_audit
    add constraint display_audit_scored_with_level
    check (
        (program_id is null and level_id is null)
        or (program_id is not null and level_id is not null)
    );

-- -----------------------------------------------------------------------------
-- Row Level Security
--
-- The three master tables are head office's, and every rep reads all of them:
-- a programme is not secret, and scoping it per branch in a policy would hide
-- the branch-null "runs everywhere" case from everyone.
-- -----------------------------------------------------------------------------

alter table display_program       enable row level security;
alter table display_program_level enable row level security;
alter table display_registration  enable row level security;

create policy "everyone reads display programs" on display_program
    for select to authenticated using (true);

create policy "everyone reads display levels" on display_program_level
    for select to authenticated using (true);

create policy "everyone reads display registrations" on display_registration
    for select to authenticated using (true);

-- -----------------------------------------------------------------------------
-- What this outlet is being audited on today
--
-- API_GetListDisplay in one function. Two sources unioned, exactly as the legacy
-- proc does it: outlets registered for a programme that requires registration,
-- and every outlet for a programme that does not — the latter at the programme's
-- lowest level, which is what `MIN(a.LevelID)` is doing over there.
--
-- `audited_at` comes from the visit rather than the day, unlike the legacy's
-- `CAST(c.VisitDate AS DATE) = @ToDay`. A shop can now be called on twice in a
-- day, and the second call should start with the programmes unscored rather
-- than inheriting the morning's answers.
-- -----------------------------------------------------------------------------

create or replace function display_programs_for(p_customer_id uuid, p_visit_id uuid)
returns table (
    program_id     uuid,
    program_code   text,
    program_name   text,
    specification  text,
    from_date      date,
    to_date        date,
    registered     boolean,
    registration_status text,
    level_id       uuid,
    level_code     text,
    level_name     text,
    required_faces integer,
    bonus_amount   bigint,
    audit_id       uuid,
    counted_faces  integer,
    achieved       boolean,
    photo_count    integer
)
language sql
stable
security invoker
set search_path = public, pg_temp
as $$
    with me as (
        select branch_id from salesperson where id = current_salesperson_id()
    ),
    live as (
        select p.*
        from display_program p, me
        where p.is_active
          and current_date between p.from_date and p.to_date
          and (p.branch_id is null or p.branch_id = me.branch_id)
    ),
    -- Registered: the outlet signed up, at the level it signed up for.
    registered as (
        select
            l.id as program_id, l.code as program_code, l.name as program_name,
            l.specification, l.from_date, l.to_date,
            true as registered, r.status as registration_status,
            lv.id as level_id, lv.code as level_code, lv.name as level_name,
            lv.required_faces, lv.bonus_amount
        from live l
        join display_registration r
            on r.program_id = l.id
           and r.customer_id = p_customer_id
           and r.status <> 'rejected'
        join display_program_level lv on lv.id = r.level_id
        where l.requires_registration
    ),
    -- Open to all: no registration, so the lowest level is the bar.
    open_to_all as (
        select
            l.id, l.code, l.name, l.specification, l.from_date, l.to_date,
            false, null::text,
            lv.id, lv.code, lv.name, lv.required_faces, lv.bonus_amount
        from live l
        join lateral (
            select *
            from display_program_level x
            where x.program_id = l.id
            order by x.required_faces, x.sort_order
            limit 1
        ) lv on true
        where not l.requires_registration
    ),
    every as (
        select * from registered
        union all
        select * from open_to_all
    )
    select
        b.program_id, b.program_code, b.program_name, b.specification,
        b.from_date, b.to_date, b.registered, b.registration_status,
        b.level_id, b.level_code, b.level_name, b.required_faces, b.bonus_amount,
        a.id, a.counted_faces, a.achieved, coalesce(a.photo_count, 0)
    from every b
    left join display_audit a
        on a.visit_id = p_visit_id and a.program_id = b.program_id
    order by b.program_name;
$$;

comment on function display_programs_for(uuid, uuid) is
    'Display programmes this outlet is audited on today, with the visit''s own '
    'result attached. The legacy API_GetListDisplay.';
