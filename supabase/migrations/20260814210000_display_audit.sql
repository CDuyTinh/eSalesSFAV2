-- =============================================================================
-- Display audit
--
-- Backs the `display_remark` step: photographic evidence of how the brand is
-- displayed in the outlet. Legacy survey_photos, without the survey machinery
-- around it.
--
-- The photo is the point. A display audit with a note and no picture is an opinion;
-- with a picture it is evidence somebody at head office can act on. Two guards in
-- `submit_display_audit` follow from that.
--
-- First, the minimum photo count is read from the step's own `config` — the same
-- `{"photo_min": 1}` that `sales_step` already carries and the client already
-- honours. Head office changes the requirement by changing data, and both sides
-- move together.
--
-- Second, every storage path is checked to exist before the row is written. Storage
-- and the database are separate systems, so "row exists" and "photo exists" can
-- otherwise drift apart — and a row pointing at nothing looks exactly like a
-- completed audit in every report that counts them.
-- =============================================================================

create table display_audit (
    id                uuid        primary key,
    visit_id          uuid        not null references visit (id) on delete cascade,
    customer_id       uuid        not null references customer (id),
    salesperson_id    uuid        not null references salesperson (id),
    audit_date        date        not null,
    note              text,

    -- Denormalised so the workflow screen can say "3 anh" without pulling them.
    photo_count       integer     not null default 0,

    client_created_at timestamptz not null,
    created_at        timestamptz not null default now(),
    updated_at        timestamptz not null default now(),

    -- One audit per visit. Redoing the step replaces the earlier attempt rather
    -- than leaving two sets of photos with no way to tell which is current.
    unique (visit_id)
);

create index on display_audit (customer_id, audit_date desc);
create index on display_audit (salesperson_id, audit_date desc);

create table display_audit_photo (
    id               uuid   primary key default gen_random_uuid(),
    display_audit_id uuid   not null references display_audit (id) on delete cascade,

    -- Object name within the visit-photos bucket, following the convention the
    -- storage policies authorise on: <salesperson_id>/<visit_id>/<file>.
    storage_path     text   not null,

    -- When the shutter fired, not when the row arrived. A queued audit can reach
    -- the server hours later, and the gap is itself worth being able to see.
    taken_at         timestamptz not null,
    lat              double precision,
    lng              double precision,
    file_size        integer,

    unique (display_audit_id, storage_path)
);

create index on display_audit_photo (display_audit_id);

create trigger display_audit_set_updated_at
    before update on display_audit
    for each row execute function set_updated_at();

-- -----------------------------------------------------------------------------
-- submit_display_audit
-- -----------------------------------------------------------------------------

create or replace function submit_display_audit(p_audit jsonb)
returns uuid
language plpgsql
security invoker
set search_path = public, pg_temp
as $$
declare
    v_audit_id   uuid := (p_audit ->> 'id')::uuid;
    v_visit_id   uuid := (p_audit ->> 'visit_id')::uuid;
    v_audit_date date := coalesce((p_audit ->> 'audit_date')::date, current_date);
    v_sp_id      uuid := current_salesperson_id();
    v_customer   uuid;
    v_photo_min  integer;
    v_photos     integer;
    v_missing    text;
begin
    if v_audit_id is null or v_visit_id is null then
        raise exception 'submit_display_audit needs both id and visit_id';
    end if;

    -- Already booked: a replay from the outbox.
    if exists (select 1 from display_audit where id = v_audit_id) then
        return v_audit_id;
    end if;

    select v.customer_id into v_customer
    from visit v
    where v.id = v_visit_id and v.salesperson_id = v_sp_id;

    if v_customer is null then
        raise exception 'visit % is not an open visit of this salesperson', v_visit_id;
    end if;

    select count(*) into v_photos
    from jsonb_array_elements(p_audit -> 'photos');

    -- The step's own configuration decides how many photos are enough. Defaults to
    -- one rather than zero: this step exists to produce a picture, and a market that
    -- has not configured the key still means at least one.
    select coalesce((s.config ->> 'photo_min')::integer, 1) into v_photo_min
    from sales_step s
    where s.form_id = 'display_remark';

    v_photo_min := coalesce(v_photo_min, 1);

    if v_photos < v_photo_min then
        raise exception
            'display audit %: % photos supplied, % required',
            v_audit_id, v_photos, v_photo_min;
    end if;

    -- Storage and the database are separate systems. Checking here is what makes
    -- "the row exists" mean "the photo exists"; the storage select policy limits
    -- this to the rep's own folder, so a path outside it reads as missing.
    select p ->> 'storage_path' into v_missing
    from jsonb_array_elements(p_audit -> 'photos') as p
    where not exists (
        select 1 from storage.objects o
        where o.bucket_id = 'visit-photos'
          and o.name = p ->> 'storage_path'
    )
    limit 1;

    if v_missing is not null then
        raise exception 'display audit %: photo % is not in storage', v_audit_id, v_missing;
    end if;

    -- Redoing the step replaces the earlier attempt, photos included through the
    -- cascade.
    delete from display_audit where visit_id = v_visit_id;

    insert into display_audit (
        id, visit_id, customer_id, salesperson_id, audit_date, note,
        photo_count, client_created_at
    )
    values (
        v_audit_id, v_visit_id, v_customer, v_sp_id, v_audit_date,
        nullif(p_audit ->> 'note', ''),
        v_photos,
        coalesce((p_audit ->> 'client_created_at')::timestamptz, now())
    );

    insert into display_audit_photo (
        display_audit_id, storage_path, taken_at, lat, lng, file_size
    )
    select
        v_audit_id,
        p ->> 'storage_path',
        coalesce((p ->> 'taken_at')::timestamptz, now()),
        (p ->> 'lat')::double precision,
        (p ->> 'lng')::double precision,
        (p ->> 'file_size')::integer
    from jsonb_array_elements(p_audit -> 'photos') as p;

    insert into visit_step_result (visit_id, form_id, completed_at, payload)
    values (
        v_visit_id,
        'display_remark',
        now(),
        jsonb_build_object('display_audit_id', v_audit_id, 'photo_count', v_photos)
    )
    on conflict (visit_id, form_id) do update
        set completed_at = excluded.completed_at,
            payload      = excluded.payload;

    return v_audit_id;
end;
$$;

revoke execute on function submit_display_audit(jsonb) from public;
grant execute on function submit_display_audit(jsonb) to authenticated;

-- -----------------------------------------------------------------------------
-- Row Level Security
-- -----------------------------------------------------------------------------

alter table display_audit       enable row level security;
alter table display_audit_photo enable row level security;

create policy "rep reads own display audits"
    on display_audit for select to authenticated
    using (salesperson_id = current_salesperson_id());

create policy "rep writes own display audits"
    on display_audit for insert to authenticated
    with check (salesperson_id = current_salesperson_id());

create policy "rep updates own display audits"
    on display_audit for update to authenticated
    using (salesperson_id = current_salesperson_id())
    with check (salesperson_id = current_salesperson_id());

-- Redoing the step deletes the visit's earlier audit.
create policy "rep deletes own display audits"
    on display_audit for delete to authenticated
    using (salesperson_id = current_salesperson_id());

create policy "rep reads own display audit photos"
    on display_audit_photo for select to authenticated
    using (
        exists (
            select 1 from display_audit a
            where a.id = display_audit_photo.display_audit_id
              and a.salesperson_id = current_salesperson_id()
        )
    );

create policy "rep writes own display audit photos"
    on display_audit_photo for insert to authenticated
    with check (
        exists (
            select 1 from display_audit a
            where a.id = display_audit_photo.display_audit_id
              and a.salesperson_id = current_salesperson_id()
        )
    );
