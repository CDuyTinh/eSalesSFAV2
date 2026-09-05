-- =============================================================================
-- Scoring an audit, one programme at a time
--
-- `submit_display_audit` wrote one row per visit and marked the step done the
-- moment it landed. An outlet in three programmes needs three rows, and the step
-- is not done until all three are scored — otherwise a rep photographs the
-- easiest display and the tile turns green over two unaudited commitments.
--
-- Two changes follow:
--
--   * the replace is scoped to the programme rather than the visit, so scoring
--     the second programme no longer deletes the first;
--   * the step is marked done only when every programme this outlet is audited
--     on has a row for this visit — and, where there are no programmes at all,
--     when the plain photo audit lands, which is what the step used to be.
--
-- counted_faces and achieved are the legacy's FaceRemark and Evaluate. Evaluate
-- is the rep's own answer, not derived from the count: the legacy dialog asks
-- for both and its authors left the derivation commented out, because a display
-- can miss the facing target and still be built to specification, or hit it with
-- the wrong products.
-- =============================================================================

create or replace function submit_display_audit(p_audit jsonb)
returns uuid
language plpgsql
security invoker
set search_path = public, pg_temp
as $$
declare
    v_audit_id   uuid := (p_audit ->> 'id')::uuid;
    v_visit_id   uuid := (p_audit ->> 'visit_id')::uuid;
    v_program_id uuid := (p_audit ->> 'program_id')::uuid;
    v_level_id   uuid := (p_audit ->> 'level_id')::uuid;
    v_faces      integer := (p_audit ->> 'counted_faces')::integer;
    v_achieved   boolean := (p_audit ->> 'achieved')::boolean;
    v_audit_date date := coalesce((p_audit ->> 'audit_date')::date, current_date);
    v_sp_id      uuid := current_salesperson_id();
    v_customer   uuid;
    v_photo_min  integer;
    v_photos     integer;
    v_missing    text;
    v_expected   integer;
    v_scored     integer;
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

    -- A level from another programme would score the display against a target
    -- nobody agreed to, and the pair is what the row's check constraint requires.
    if (v_program_id is null) <> (v_level_id is null) then
        raise exception 'display audit %: program and level must travel together', v_audit_id;
    end if;

    if v_program_id is not null and not exists (
        select 1 from display_program_level
        where id = v_level_id and program_id = v_program_id
    ) then
        raise exception
            'display audit %: level % does not belong to program %',
            v_audit_id, v_level_id, v_program_id;
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

    -- Rescoring one programme replaces that programme's earlier attempt, photos
    -- included through the cascade, and leaves the others alone.
    delete from display_audit
    where visit_id = v_visit_id
      and program_id is not distinct from v_program_id;

    insert into display_audit (
        id, visit_id, customer_id, salesperson_id, audit_date, note,
        photo_count, client_created_at,
        program_id, level_id, counted_faces, achieved
    )
    values (
        v_audit_id, v_visit_id, v_customer, v_sp_id, v_audit_date,
        nullif(p_audit ->> 'note', ''),
        v_photos,
        coalesce((p_audit ->> 'client_created_at')::timestamptz, now()),
        v_program_id, v_level_id, v_faces, v_achieved
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

    -- How many programmes this outlet owes, and how many now have a row.
    select count(*) into v_expected
    from display_programs_for(v_customer, v_visit_id);

    select count(*) into v_scored
    from display_audit
    where visit_id = v_visit_id and program_id is not null;

    -- Done when every programme is scored. With no programmes the plain audit
    -- just written is the whole of the step, which is what it was before.
    if v_expected = 0 or v_scored >= v_expected then
        insert into visit_step_result (visit_id, form_id, completed_at, payload)
        values (
            v_visit_id,
            'display_remark',
            now(),
            jsonb_build_object(
                'display_audit_id', v_audit_id,
                'photo_count', v_photos,
                'programs_scored', v_scored,
                'programs_expected', v_expected
            )
        )
        on conflict (visit_id, form_id) do update
            set completed_at = excluded.completed_at,
                payload      = excluded.payload;
    end if;

    return v_audit_id;
end;
$$;

revoke execute on function submit_display_audit(jsonb) from public;
grant execute on function submit_display_audit(jsonb) to authenticated;
