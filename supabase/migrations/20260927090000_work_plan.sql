-- =============================================================================
-- Kế hoạch làm việc
--
-- The largest gap left, and the one place where the legacy holds the same thing
-- twice.
--
-- Over there MY_TASK reads OM_MakingPlan and WORKING_PLAN writes OM_WorkingPlan.
-- Set the two side by side and they are the same row: a rep, a work-with
-- category, a time window, content, a note, a status, a reason, and a history
-- table beside it. What differs is who typed it and two alphabets for the same
-- six states --
--
--     OM_MakingPlan   H  W  A  R  C  V
--     OM_WorkingPlan  O  W  A  R  P  C
--
-- H and O are both "drafted", C and P are both "finished", V and C are both
-- "called off". The letter C means finished in one table and called off in the
-- other, which is the kind of divergence that only survives because the two
-- screens never meet.
--
-- Here it is one table with one status enum. "My task" is not a different
-- object; it is the predicate `author_id <> salesperson_id` -- somebody else
-- planned my day. "Working plan" is the other half of the same list.
--
-- What is deliberately NOT ported:
--   * EDAC / field training scoring (Edac/Kpi, Edac/Training, WorkWithCriteria).
--     That is a coaching module with its own criteria tree, its own scoring and
--     its own manager screens; the rep's app only ever reads its results. The
--     `field_training` flag is kept so a plan can say it is one.
--   * RepeatID (recurring plans) -- created by a manager tool that does not
--     exist here, and nothing in the rep's app would ever set it.
--   * OM_MakingPlanGuest.
-- =============================================================================

create type work_plan_status as enum (
    'draft',      -- H / O: written, not sent
    'submitted',  -- W: waiting for the manager
    'approved',   -- A
    'rejected',   -- R: sent back, reason required
    'completed',  -- C / P: done, result required
    'cancelled'   -- V / C: called off, reason required
);

/** Who or what the plan is with. `OM_MakingPlanObject.ObjectType`. */
create type work_plan_object_kind as enum (
    'customer',     -- C
    'salesperson',  -- S
    'branch'        -- B, the distributor
);

-- -----------------------------------------------------------------------------
-- The catalogue of work types. OM_WorkWithCategory.
-- -----------------------------------------------------------------------------

create table work_with_category (
    id          uuid    primary key default gen_random_uuid(),
    code        text    not null unique,
    name        text    not null,

    /** An EDAC coaching visit. The scoring lives elsewhere; this only labels it. */
    field_training boolean not null default false,
    /** The plan is about a trade programme rather than a route day. */
    trade_program  boolean not null default false,

    /**
     * What the plan must name, or null when the work has no object at all --
     * `WORK_TYPE_NO_OBJECT`, which the legacy spells as an empty string and
     * which is a real case: a training day is with nobody.
     */
    object_kind work_plan_object_kind,

    /** Advisory bounds head office sets on the day's sales. Both optional. */
    min_sales   bigint,
    max_sales   bigint,

    sort_order  integer not null default 0,
    is_active   boolean not null default true,

    created_at  timestamptz not null default now(),
    updated_at  timestamptz not null default now(),

    constraint work_with_category_sales_range
        check (min_sales is null or max_sales is null or max_sales >= min_sales)
);

alter table work_with_category enable row level security;

create policy "everyone reads the work catalogue"
    on work_with_category for select to authenticated
    using (is_active);

-- -----------------------------------------------------------------------------
-- The plan
-- -----------------------------------------------------------------------------

create table work_plan (
    id             uuid primary key default gen_random_uuid(),

    /** Whose day this plans. */
    salesperson_id uuid not null references salesperson (id),
    /**
     * Who wrote it. Null means head office or a manager tool -- there is no
     * supervisor in this app, so a plan the rep did not write arrives from
     * outside it, the way programmes do. `author_id <> salesperson_id` is the
     * legacy's `IsAssignToMe`.
     */
    author_id      uuid references salesperson (id),
    branch_id      uuid not null references branch (id),

    category_id    uuid not null references work_with_category (id),

    content        text not null,
    note           text,
    place          text,

    /**
     * The window. Stored as timestamps, not a date plus two times: the legacy
     * carries WorkingFrom and WorkingTo as datetimes and its own form lets a
     * plan run past midnight, which a date column would quietly truncate.
     */
    from_at        timestamptz not null,
    to_at          timestamptz not null,

    status         work_plan_status not null default 'draft',

    /** What the rep reported when finishing. Required to complete. */
    result         text,
    /** Why it was sent back or called off. Required for both. */
    reason         text,

    submitted_at   timestamptz,
    approved_at    timestamptz,
    rejected_at    timestamptz,
    completed_at   timestamptz,
    cancelled_at   timestamptz,

    created_at     timestamptz not null default now(),
    updated_at     timestamptz not null default now(),

    constraint work_plan_window check (to_at > from_at),
    constraint work_plan_content_not_blank check (btrim(content) <> '')
);

create index on work_plan (salesperson_id, from_at desc);
create index on work_plan (salesperson_id, status);
create index on work_plan (category_id);

alter table work_plan enable row level security;

create policy "rep reads own plans"
    on work_plan for select to authenticated
    using (salesperson_id = current_salesperson_id());

create policy "rep writes own plans"
    on work_plan for insert to authenticated
    with check (
        salesperson_id = current_salesperson_id()
        and author_id = current_salesperson_id()
    );

/**
 * The rep may edit their own plan only while it is still a draft.
 *
 * Both halves matter. USING gates which rows the update may touch, WITH CHECK
 * gates what they may become -- without the second, a rep could set a draft
 * straight to 'approved' and skip the manager entirely. The status machine
 * lives in set_work_plan_status(), which is security definer for exactly that
 * reason; this policy is the floor under it.
 */
create policy "rep edits own draft"
    on work_plan for update to authenticated
    using (
        salesperson_id = current_salesperson_id()
        and author_id = current_salesperson_id()
        and status = 'draft'
    )
    with check (
        salesperson_id = current_salesperson_id()
        and author_id = current_salesperson_id()
        and status = 'draft'
    );

create policy "rep deletes own draft"
    on work_plan for delete to authenticated
    using (
        salesperson_id = current_salesperson_id()
        and author_id = current_salesperson_id()
        and status = 'draft'
    );

-- -----------------------------------------------------------------------------

create table work_plan_object (
    id             uuid primary key default gen_random_uuid(),
    plan_id        uuid not null references work_plan (id) on delete cascade,
    kind           work_plan_object_kind not null,

    customer_id    uuid references customer (id),
    target_id      uuid references salesperson (id),
    target_branch_id uuid references branch (id),

    created_at     timestamptz not null default now(),

    /** Exactly one target, and the one the kind names. */
    constraint work_plan_object_target check (
        (kind = 'customer'    and customer_id is not null
                              and target_id is null and target_branch_id is null)
     or (kind = 'salesperson' and target_id is not null
                              and customer_id is null and target_branch_id is null)
     or (kind = 'branch'      and target_branch_id is not null
                              and customer_id is null and target_id is null)
    )
);

create unique index on work_plan_object (plan_id, kind, coalesce(customer_id, target_id, target_branch_id));

alter table work_plan_object enable row level security;

create policy "rep reads objects of own plans"
    on work_plan_object for select to authenticated
    using (exists (
        select 1 from work_plan p
        where p.id = plan_id and p.salesperson_id = current_salesperson_id()
    ));

create policy "rep writes objects of own draft"
    on work_plan_object for insert to authenticated
    with check (exists (
        select 1 from work_plan p
        where p.id = plan_id
          and p.salesperson_id = current_salesperson_id()
          and p.author_id = current_salesperson_id()
          and p.status = 'draft'
    ));

create policy "rep clears objects of own draft"
    on work_plan_object for delete to authenticated
    using (exists (
        select 1 from work_plan p
        where p.id = plan_id
          and p.salesperson_id = current_salesperson_id()
          and p.author_id = current_salesperson_id()
          and p.status = 'draft'
    ));

-- -----------------------------------------------------------------------------

/**
 * Every transition, in order. OM_MakingPlanHistory.
 *
 * The plan carries the latest of each timestamp for the sake of a cheap list
 * query; this is what actually happened, including a rejection the manager
 * later reversed. Insert-only by policy -- a history you can edit is a note.
 */
create table work_plan_history (
    id         uuid primary key default gen_random_uuid(),
    plan_id    uuid not null references work_plan (id) on delete cascade,
    status     work_plan_status not null,
    /** Null when the change came from outside this app. */
    actor_id   uuid references salesperson (id),
    reason     text,
    created_at timestamptz not null default now()
);

create index on work_plan_history (plan_id, created_at);

alter table work_plan_history enable row level security;

create policy "rep reads history of own plans"
    on work_plan_history for select to authenticated
    using (exists (
        select 1 from work_plan p
        where p.id = plan_id and p.salesperson_id = current_salesperson_id()
    ));

-- -----------------------------------------------------------------------------

create table work_plan_photo (
    id           uuid primary key default gen_random_uuid(),
    plan_id      uuid not null references work_plan (id) on delete cascade,
    storage_path text not null,
    taken_at     timestamptz,
    lat          double precision,
    lng          double precision,
    file_size    integer,
    created_at   timestamptz not null default now()
);

create index on work_plan_photo (plan_id);

alter table work_plan_photo enable row level security;

create policy "rep reads photos of own plans"
    on work_plan_photo for select to authenticated
    using (exists (
        select 1 from work_plan p
        where p.id = plan_id and p.salesperson_id = current_salesperson_id()
    ));

/** Evidence goes on when the plan is closed out, so an open plan accepts it. */
create policy "rep adds photos to own open plan"
    on work_plan_photo for insert to authenticated
    with check (exists (
        select 1 from work_plan p
        where p.id = plan_id
          and p.salesperson_id = current_salesperson_id()
          and p.status in ('draft', 'submitted', 'approved')
    ));

-- -----------------------------------------------------------------------------

create trigger work_with_category_set_updated_at
    before update on work_with_category
    for each row execute function set_updated_at();

create trigger work_plan_set_updated_at
    before update on work_plan
    for each row execute function set_updated_at();
