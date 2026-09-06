-- =============================================================================
-- POSM
--
-- `posm_status` is a four-question questionnaire — "Có poster treo đúng vị trí?"
-- and so on. That is a Perfect Store audit wearing POSM's name. The step it
-- replaces is asset management: the company lends a shop a fridge, a rack, a
-- light box; the rep's job on the call is to find each one, count it, say what
-- condition it is in and photograph it.
--
-- The legacy shape, and what each piece becomes here:
--
--   IN_POSMHeader     -> posm_program        the programme and its window
--   IN_POSMBranch     -> posm_program.branch_id + posm_program_item
--   IN_POSMInvt       -> posm_program_item   which assets it hands out
--   IN_Inventory      -> posm_item           the asset itself, unit and picture
--   IN_POSMCust       -> posm_registration   Qty / AppQty / Status per outlet
--   (fs_GetPosmRecallQty) -> posm_placement  what is physically at the outlet
--   PPC_Posm          -> posm_check          Qty, Result, Suggest, Remark
--   PPC_PosmImage1    -> posm_check_photo
--
-- `posm_placement` is the one deliberate simplification. The legacy derives the
-- in-use quantity by subtracting recalls from POSM lines on completed sales
-- orders — a calculation that only makes sense because POSM travels on order
-- documents over there. Here it is a stated holding: this outlet has this many
-- of this asset. A delivery raises it, a recall lowers it, and the rep's count
-- is checked against it rather than against an inference.
--
-- Registration, delivery and recall are read-only from this app for now: the
-- rows exist and the screen shows them, but nothing here writes them. That is
-- the honest state of the port, not a modelling opinion.
-- =============================================================================

create table posm_item (
    id         uuid    primary key default gen_random_uuid(),
    code       text    not null unique,
    name       text    not null,

    /** StkUnit's description: Cái, Bộ, Chiếc. What the rep counts in. */
    unit_name  text    not null default 'Cái',

    image_url  text,
    is_active  boolean not null default true,
    created_at timestamptz not null default now()
);

create table posm_program (
    id        uuid    primary key default gen_random_uuid(),
    code      text    not null unique,
    name      text    not null,

    -- The window the programme is live in. A rep calling outside it sees the
    -- assets it left behind but cannot register against it.
    from_date date    not null,
    to_date   date    not null,

    /** Null runs the programme in every branch. */
    branch_id uuid    references branch (id),

    is_active boolean not null default true,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),

    constraint posm_program_dates check (to_date >= from_date)
);

create trigger posm_program_set_updated_at
    before update on posm_program
    for each row execute function set_updated_at();

create table posm_program_item (
    id           uuid    primary key default gen_random_uuid(),
    program_id   uuid    not null references posm_program (id) on delete cascade,
    posm_item_id uuid    not null references posm_item (id),

    /** How many of this asset one outlet may hold. Zero means no ceiling. */
    max_per_customer integer not null default 0 check (max_per_customer >= 0),

    sort_order   integer not null default 0,

    unique (program_id, posm_item_id)
);

-- -----------------------------------------------------------------------------
-- What the outlet asked for, and what it actually has
-- -----------------------------------------------------------------------------

create table posm_registration (
    id            uuid    primary key default gen_random_uuid(),
    program_id    uuid    not null references posm_program (id) on delete cascade,
    posm_item_id  uuid    not null references posm_item (id),
    customer_id   uuid    not null references customer (id) on delete cascade,

    /** Qty: what the rep put in for. */
    regis_qty     integer not null check (regis_qty > 0),
    /** AppQty: what head office allowed, zero until they rule. */
    approved_qty  integer not null default 0 check (approved_qty >= 0),
    /** How much of the approved quantity has actually reached the shop. */
    delivered_qty integer not null default 0 check (delivered_qty >= 0),

    -- The legacy's H / C / D.
    status        text    not null default 'pending'
                  check (status in ('pending', 'approved', 'rejected')),

    registered_at date    not null default current_date,
    created_at    timestamptz not null default now(),
    updated_at    timestamptz not null default now(),

    unique (program_id, posm_item_id, customer_id),

    -- Approving more than was asked for, or delivering more than was approved,
    -- are both back-office mistakes worth refusing at the table.
    constraint posm_registration_approved_within_request
        check (approved_qty <= regis_qty),
    constraint posm_registration_delivered_within_approved
        check (delivered_qty <= approved_qty)
);

create index on posm_registration (customer_id);

create trigger posm_registration_set_updated_at
    before update on posm_registration
    for each row execute function set_updated_at();

create table posm_placement (
    id           uuid    primary key default gen_random_uuid(),
    customer_id  uuid    not null references customer (id) on delete cascade,
    program_id   uuid    not null references posm_program (id),
    posm_item_id uuid    not null references posm_item (id),

    /** How many are at the shop. A recall that empties it deletes the row. */
    qty          integer not null check (qty > 0),

    placed_at    date    not null default current_date,
    created_at   timestamptz not null default now(),
    updated_at   timestamptz not null default now(),

    unique (customer_id, program_id, posm_item_id)
);

create index on posm_placement (customer_id);

create trigger posm_placement_set_updated_at
    before update on posm_placement
    for each row execute function set_updated_at();

-- -----------------------------------------------------------------------------
-- The check itself
-- -----------------------------------------------------------------------------

-- API_GetPOSMStatus's three codes, which are the whole vocabulary the rep has
-- for an asset's condition. Stored as an enum rather than a lookup table: three
-- values that have not changed in the legacy's lifetime are not configuration.
create type posm_condition as enum (
    'usable',      -- posm_status_can_use
    'repairable',  -- posm_status_can_repair
    'unusable'     -- posm_status_can_not_use
);

create table posm_check (
    id                uuid    primary key,
    visit_id          uuid    not null references visit (id) on delete cascade,
    customer_id       uuid    not null references customer (id),
    salesperson_id    uuid    not null references salesperson (id),
    program_id        uuid    not null references posm_program (id),
    posm_item_id      uuid    not null references posm_item (id),

    /** Qty: what the rep found, which is the number worth having. */
    counted_qty       integer not null check (counted_qty >= 0),

    /** Result: the condition, from the rep's own eyes. */
    condition         posm_condition not null,

    /** Remark: free note. Suggest: what the rep thinks should happen next. */
    remark            text,
    suggestion        text,

    check_date        date    not null default current_date,
    photo_count       integer not null default 0,

    client_created_at timestamptz not null,
    created_at        timestamptz not null default now(),
    updated_at        timestamptz not null default now(),

    -- One check per asset per visit. Redoing it replaces the earlier attempt
    -- rather than leaving two counts with no way to tell which is current.
    unique (visit_id, program_id, posm_item_id)
);

create index on posm_check (customer_id, check_date desc);
create index on posm_check (salesperson_id, check_date desc);

create trigger posm_check_set_updated_at
    before update on posm_check
    for each row execute function set_updated_at();

create table posm_check_photo (
    id            uuid   primary key default gen_random_uuid(),
    posm_check_id uuid   not null references posm_check (id) on delete cascade,

    -- Object name within the visit-photos bucket, following the convention the
    -- storage policies authorise on: <salesperson_id>/<visit_id>/<file>.
    storage_path  text   not null,

    taken_at      timestamptz not null,
    lat           double precision,
    lng           double precision,
    file_size     integer,

    unique (posm_check_id, storage_path)
);

create index on posm_check_photo (posm_check_id);

-- -----------------------------------------------------------------------------
-- Row Level Security
--
-- The catalogue and the programmes are head office's and every rep reads them.
-- Placements and registrations are per outlet, and a rep reads all of them for
-- the same reason they read every customer: the route decides who they meet,
-- not a policy. The checks are the rep's own work, and follow display_audit.
-- -----------------------------------------------------------------------------

alter table posm_item         enable row level security;
alter table posm_program      enable row level security;
alter table posm_program_item enable row level security;
alter table posm_registration enable row level security;
alter table posm_placement    enable row level security;
alter table posm_check        enable row level security;
alter table posm_check_photo  enable row level security;

create policy "everyone reads posm items" on posm_item
    for select to authenticated using (true);

create policy "everyone reads posm programs" on posm_program
    for select to authenticated using (true);

create policy "everyone reads posm program items" on posm_program_item
    for select to authenticated using (true);

create policy "everyone reads posm registrations" on posm_registration
    for select to authenticated using (true);

create policy "everyone reads posm placements" on posm_placement
    for select to authenticated using (true);

create policy "rep reads own posm checks"
    on posm_check for select to authenticated
    using (salesperson_id = current_salesperson_id());

create policy "rep writes own posm checks"
    on posm_check for insert to authenticated
    with check (salesperson_id = current_salesperson_id());

create policy "rep updates own posm checks"
    on posm_check for update to authenticated
    using (salesperson_id = current_salesperson_id())
    with check (salesperson_id = current_salesperson_id());

-- Redoing an asset's check deletes the visit's earlier one.
create policy "rep deletes own posm checks"
    on posm_check for delete to authenticated
    using (salesperson_id = current_salesperson_id());

create policy "rep reads own posm check photos"
    on posm_check_photo for select to authenticated
    using (
        exists (
            select 1 from posm_check c
            where c.id = posm_check_photo.posm_check_id
              and c.salesperson_id = current_salesperson_id()
        )
    );

create policy "rep writes own posm check photos"
    on posm_check_photo for insert to authenticated
    with check (
        exists (
            select 1 from posm_check c
            where c.id = posm_check_photo.posm_check_id
              and c.salesperson_id = current_salesperson_id()
        )
    );

-- -----------------------------------------------------------------------------
-- What is at this outlet, and how this visit found it
--
-- API_GetPOSM_IsUsing @Type = 'U', with the visit's own check attached. Keyed by
-- the visit rather than the date for the same reason the display listing is: a
-- shop can be called on twice in a day, and the second call starts unchecked.
-- -----------------------------------------------------------------------------

create or replace function posm_at_customer(p_customer_id uuid, p_visit_id uuid)
returns table (
    program_id     uuid,
    program_code   text,
    program_name   text,
    posm_item_id   uuid,
    item_code      text,
    item_name      text,
    unit_name      text,
    image_url      text,
    placed_qty     integer,
    placed_at      date,
    check_id       uuid,
    counted_qty    integer,
    condition      text,
    remark         text,
    suggestion     text,
    photo_count    integer
)
language sql
stable
security invoker
set search_path = public, pg_temp
as $$
    select
        pl.program_id, pr.code, pr.name,
        pl.posm_item_id, it.code, it.name, it.unit_name, it.image_url,
        pl.qty, pl.placed_at,
        ck.id, ck.counted_qty, ck.condition::text, ck.remark, ck.suggestion,
        coalesce(ck.photo_count, 0)
    from posm_placement pl
    join posm_program pr on pr.id = pl.program_id
    join posm_item it on it.id = pl.posm_item_id
    left join posm_check ck
        on ck.visit_id = p_visit_id
       and ck.program_id = pl.program_id
       and ck.posm_item_id = pl.posm_item_id
    where pl.customer_id = p_customer_id
      -- An asset stays checkable after its programme closes: it is still in the
      -- shop, and a finished programme is exactly when someone should be asking
      -- whether the fridge is coming back.
      and pr.is_active
      and it.is_active
    order by pr.name, it.name;
$$;

comment on function posm_at_customer(uuid, uuid) is
    'POSM physically at this outlet, with the visit''s own check attached. '
    'The legacy API_GetPOSM_IsUsing, type U.';

-- -----------------------------------------------------------------------------
-- What this outlet has registered for
--
-- API_GetPOSM_IsUsing @Type = 'R'. Read-only here: the rep sees where each
-- request stands, and registering is still a back-office act.
-- -----------------------------------------------------------------------------

create or replace function posm_registrations_for(p_customer_id uuid)
returns table (
    program_id    uuid,
    program_code  text,
    program_name  text,
    from_date     date,
    to_date       date,
    posm_item_id  uuid,
    item_code     text,
    item_name     text,
    unit_name     text,
    image_url     text,
    regis_qty     integer,
    approved_qty  integer,
    delivered_qty integer,
    status        text,
    registered_at date
)
language sql
stable
security invoker
set search_path = public, pg_temp
as $$
    select
        rg.program_id, pr.code, pr.name, pr.from_date, pr.to_date,
        rg.posm_item_id, it.code, it.name, it.unit_name, it.image_url,
        rg.regis_qty, rg.approved_qty, rg.delivered_qty, rg.status, rg.registered_at
    from posm_registration rg
    join posm_program pr on pr.id = rg.program_id
    join posm_item it on it.id = rg.posm_item_id
    where rg.customer_id = p_customer_id
    order by rg.registered_at desc, pr.name, it.name;
$$;

comment on function posm_registrations_for(uuid) is
    'POSM this outlet has registered for, with approval and delivery progress. '
    'The legacy API_GetPOSM_IsUsing, type R.';

-- -----------------------------------------------------------------------------
-- Recording one asset's check
--
-- InsertPosmChecking, with the checks the .NET handler never made: that the
-- visit is this rep's, that the photos are really in storage, and that the step
-- is only marked done once every asset at the outlet has been looked at.
-- -----------------------------------------------------------------------------

create or replace function submit_posm_check(p_check jsonb)
returns uuid
language plpgsql
security invoker
set search_path = public, pg_temp
as $$
declare
    v_check_id   uuid := (p_check ->> 'id')::uuid;
    v_visit_id   uuid := (p_check ->> 'visit_id')::uuid;
    v_program_id uuid := (p_check ->> 'program_id')::uuid;
    v_item_id    uuid := (p_check ->> 'posm_item_id')::uuid;
    v_qty        integer := (p_check ->> 'counted_qty')::integer;
    v_condition  posm_condition := (p_check ->> 'condition')::posm_condition;
    v_date       date := coalesce((p_check ->> 'check_date')::date, current_date);
    v_sp_id      uuid := current_salesperson_id();
    v_customer   uuid;
    v_photo_min  integer;
    v_photos     integer;
    v_missing    text;
    v_expected   integer;
    v_checked    integer;
begin
    if v_check_id is null or v_visit_id is null then
        raise exception 'submit_posm_check needs both id and visit_id';
    end if;

    -- The condition is the point of the check; a count with no verdict says the
    -- rep found the fridge and nothing about the state it is in.
    if p_check ->> 'condition' is null or v_qty is null then
        raise exception 'posm check %: counted_qty and condition are both required', v_check_id;
    end if;

    -- Already booked: a replay from the outbox.
    if exists (select 1 from posm_check where id = v_check_id) then
        return v_check_id;
    end if;

    select v.customer_id into v_customer
    from visit v
    where v.id = v_visit_id and v.salesperson_id = v_sp_id;

    if v_customer is null then
        raise exception 'visit % is not a visit of this salesperson', v_visit_id;
    end if;

    -- Checking an asset the shop does not hold would put a count against
    -- something nobody lent it.
    if not exists (
        select 1 from posm_placement
        where customer_id = v_customer
          and program_id = v_program_id
          and posm_item_id = v_item_id
    ) then
        raise exception
            'posm check %: this outlet holds no item % from program %',
            v_check_id, v_item_id, v_program_id;
    end if;

    select count(*) into v_photos
    from jsonb_array_elements(coalesce(p_check -> 'photos', '[]'::jsonb));

    -- The step's own configuration decides how many photos are enough, exactly
    -- as the display audit reads photo_min. Defaulting to one: an asset the rep
    -- says is broken and did not photograph is an assertion, not a report.
    select coalesce((s.config ->> 'photo_min')::integer, 1) into v_photo_min
    from sales_step s
    where s.form_id = 'posm_status';

    v_photo_min := coalesce(v_photo_min, 1);

    if v_photos < v_photo_min then
        raise exception
            'posm check %: % photos supplied, % required',
            v_check_id, v_photos, v_photo_min;
    end if;

    -- Storage and the database are separate systems. Checking here is what makes
    -- "the row exists" mean "the photo exists"; the storage select policy limits
    -- this to the rep's own folder, so a path outside it reads as missing.
    select p ->> 'storage_path' into v_missing
    from jsonb_array_elements(coalesce(p_check -> 'photos', '[]'::jsonb)) as p
    where not exists (
        select 1 from storage.objects o
        where o.bucket_id = 'visit-photos'
          and o.name = p ->> 'storage_path'
    )
    limit 1;

    if v_missing is not null then
        raise exception 'posm check %: photo % is not in storage', v_check_id, v_missing;
    end if;

    -- Rechecking one asset replaces that asset's earlier attempt, photos
    -- included through the cascade, and leaves the others alone.
    delete from posm_check
    where visit_id = v_visit_id
      and program_id = v_program_id
      and posm_item_id = v_item_id;

    insert into posm_check (
        id, visit_id, customer_id, salesperson_id, program_id, posm_item_id,
        counted_qty, condition, remark, suggestion,
        check_date, photo_count, client_created_at
    )
    values (
        v_check_id, v_visit_id, v_customer, v_sp_id, v_program_id, v_item_id,
        v_qty, v_condition,
        nullif(p_check ->> 'remark', ''),
        nullif(p_check ->> 'suggestion', ''),
        v_date, v_photos,
        coalesce((p_check ->> 'client_created_at')::timestamptz, now())
    );

    insert into posm_check_photo (
        posm_check_id, storage_path, taken_at, lat, lng, file_size
    )
    select
        v_check_id,
        p ->> 'storage_path',
        coalesce((p ->> 'taken_at')::timestamptz, now()),
        (p ->> 'lat')::double precision,
        (p ->> 'lng')::double precision,
        (p ->> 'file_size')::integer
    from jsonb_array_elements(coalesce(p_check -> 'photos', '[]'::jsonb)) as p;

    -- How many assets this outlet holds, and how many now have a check.
    select count(*) into v_expected
    from posm_at_customer(v_customer, v_visit_id);

    select count(*) into v_checked
    from posm_check where visit_id = v_visit_id;

    -- Done only when every asset has been looked at. Half a shop's POSM checked
    -- is a step that has not happened, however green the tile looks.
    if v_expected > 0 and v_checked >= v_expected then
        insert into visit_step_result (visit_id, form_id, completed_at, payload)
        values (
            v_visit_id,
            'posm_status',
            now(),
            jsonb_build_object(
                'items_checked', v_checked,
                'items_expected', v_expected
            )
        )
        on conflict (visit_id, form_id) do update
            set completed_at = excluded.completed_at,
                payload      = excluded.payload;
    end if;

    return v_check_id;
end;
$$;

revoke execute on function submit_posm_check(jsonb) from public;
grant execute on function submit_posm_check(jsonb) to authenticated;

-- -----------------------------------------------------------------------------
-- An outlet with no POSM at all
--
-- Nothing to count, so nothing to submit — and a step that can never be
-- completed would block check-out on a shop that simply has none of the
-- company's furniture. Marked done on open, which is what the legacy's own
-- "posm is empty" alert amounts to once the rep has read it.
-- -----------------------------------------------------------------------------

create or replace function complete_empty_posm_step(p_visit_id uuid)
returns boolean
language plpgsql
security invoker
set search_path = public, pg_temp
as $$
declare
    v_customer uuid;
    v_count    integer;
begin
    select v.customer_id into v_customer
    from visit v
    where v.id = p_visit_id and v.salesperson_id = current_salesperson_id();

    if v_customer is null then
        raise exception 'visit % is not a visit of this salesperson', p_visit_id;
    end if;

    select count(*) into v_count from posm_at_customer(v_customer, p_visit_id);

    if v_count > 0 then
        return false;
    end if;

    insert into visit_step_result (visit_id, form_id, completed_at, payload)
    values (
        p_visit_id, 'posm_status', now(),
        jsonb_build_object('items_checked', 0, 'items_expected', 0)
    )
    on conflict (visit_id, form_id) do update
        set completed_at = excluded.completed_at,
            payload      = excluded.payload;

    return true;
end;
$$;

revoke execute on function complete_empty_posm_step(uuid) from public;
grant execute on function complete_empty_posm_step(uuid) to authenticated;

-- -----------------------------------------------------------------------------
-- The step's photo limits, matching POSM_IMAGE_REQUIRED and POSM_Image.
-- -----------------------------------------------------------------------------

update sales_step
set config = coalesce(config, '{}'::jsonb) || jsonb_build_object(
    'photo_min', 1,
    'photo_max', 4
)
where form_id = 'posm_status';

-- The questionnaire that used to stand in for this step. Its questions are a
-- store audit, not an asset check, and leaving it attached to posm_status would
-- leave two screens claiming the same step. Deactivated rather than dropped:
-- anywhere it has already been answered, the answers point at these questions
-- and deleting them would take the answers' meaning with them.
update survey_type set is_active = false where form_id = 'posm_status';

-- Where it was never answered, it is only clutter.
delete from survey_type st
where st.form_id = 'posm_status'
  and not exists (select 1 from survey s where s.survey_type_id = st.id);
