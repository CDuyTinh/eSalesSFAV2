-- =============================================================================
-- Ba việc còn lại của bước POSM
--
--   posm_catalogue_for       what this outlet may still be signed up for
--   submit_posm_registration InsertPosmRegis
--   submit_posm_movement     InsertDeliverPosm, both of its order types
-- =============================================================================

-- -----------------------------------------------------------------------------
-- What may be asked for
--
-- Every asset on a programme live for this branch today, with what the outlet
-- already holds and already asked for, so the screen can show the ceiling rather
-- than let the rep discover it from a rejection.
-- -----------------------------------------------------------------------------

create function posm_catalogue_for(p_customer_id uuid)
returns table (
    program_id       uuid,
    program_code     text,
    program_name     text,
    posm_item_id     uuid,
    item_code        text,
    item_name        text,
    unit_name        text,
    image_url        text,
    max_per_customer integer,
    registered_qty   integer,
    approved_qty     integer,
    delivered_qty    integer,
    placed_qty       integer,
    registration_status text
)
language sql
stable
security invoker
set search_path = public, pg_temp
as $$
    with me as (
        select branch_id from salesperson where id = current_salesperson_id()
    )
    select
        p.id, p.code, p.name,
        it.id, it.code, it.name, it.unit_name, it.image_url,
        pi.max_per_customer,
        coalesce(r.regis_qty, 0),
        coalesce(r.approved_qty, 0),
        coalesce(r.delivered_qty, 0),
        coalesce(pl.qty, 0),
        r.status
    from posm_program p
    cross join me
    join posm_program_item pi on pi.program_id = p.id
    join posm_item it on it.id = pi.posm_item_id and it.is_active
    left join posm_registration r
        on r.program_id = p.id and r.posm_item_id = it.id and r.customer_id = p_customer_id
    left join posm_placement pl
        on pl.program_id = p.id and pl.posm_item_id = it.id and pl.customer_id = p_customer_id
    where p.is_active
      and current_date between p.from_date and p.to_date
      and (p.branch_id is null or p.branch_id = me.branch_id)
    order by p.name, pi.sort_order, it.name;
$$;

comment on function posm_catalogue_for(uuid) is
    'POSM this outlet may be registered for today, with what it already has. API_GetListPOSM plus the outlet''s own rows.';

-- -----------------------------------------------------------------------------
-- Asking for it
-- -----------------------------------------------------------------------------

create function submit_posm_registration(p_payload jsonb)
returns integer
language plpgsql
security invoker
set search_path = public, pg_temp
as $$
declare
    v_visit_id uuid := (p_payload ->> 'visit_id')::uuid;
    v_sp_id    uuid := current_salesperson_id();
    v_branch   uuid;
    v_customer uuid;
    v_line     jsonb;
    v_program  uuid;
    v_item     uuid;
    v_qty      integer;
    v_max      integer;
    v_status   text;
    v_count    integer := 0;
begin
    if v_visit_id is null then
        raise exception 'submit_posm_registration needs a visit_id';
    end if;

    select v.customer_id into v_customer
    from visit v
    where v.id = v_visit_id and v.salesperson_id = v_sp_id;

    if v_customer is null then
        raise exception 'visit % is not a visit of this salesperson', v_visit_id;
    end if;

    select branch_id into v_branch from salesperson where id = v_sp_id;

    for v_line in select * from jsonb_array_elements(p_payload -> 'lines')
    loop
        v_program := (v_line ->> 'program_id')::uuid;
        v_item    := (v_line ->> 'posm_item_id')::uuid;
        v_qty     := coalesce((v_line ->> 'qty')::integer, 0);

        if v_qty < 1 then
            raise exception 'a registration line must ask for at least one';
        end if;

        -- The asset has to be on a programme running here today. Anything else is
        -- a client working from a stale catalogue, and a request nobody can fill.
        select pi.max_per_customer into v_max
        from posm_program p
        join posm_program_item pi on pi.program_id = p.id
        where p.id = v_program
          and pi.posm_item_id = v_item
          and p.is_active
          and current_date between p.from_date and p.to_date
          and (p.branch_id is null or p.branch_id = v_branch);

        if not found then
            raise exception 'this POSM is not on a programme running here today';
        end if;

        if coalesce(v_max, 0) > 0 and v_qty > v_max then
            raise exception
                'one outlet may hold at most % of this asset', v_max;
        end if;

        select status into v_status
        from posm_registration
        where program_id = v_program and posm_item_id = v_item and customer_id = v_customer;

        -- One row per outlet and asset, where the legacy files a new LineRef per
        -- request. The price of that is here: a request head office has already
        -- ruled on cannot be quietly rewritten, so it is refused and named
        -- instead of overwriting an approval or reviving a refusal.
        if v_status = 'approved' then
            raise exception
                'this outlet already has an approved request for this POSM';
        elsif v_status = 'rejected' then
            raise exception
                'head office refused this POSM for this outlet';
        end if;

        insert into posm_registration (
            program_id, posm_item_id, customer_id,
            regis_qty, approved_qty, delivered_qty, status,
            salesperson_id, visit_id, reason, registered_at
        )
        values (
            v_program, v_item, v_customer,
            v_qty, 0, 0, 'pending',
            v_sp_id, v_visit_id, nullif(btrim(v_line ->> 'reason'), ''), current_date
        )
        on conflict (program_id, posm_item_id, customer_id) do update
            -- Still pending, so the rep is restating the ask rather than adding
            -- to it: two conversations in one week about the same rack are one
            -- request for whatever number they settled on.
            set regis_qty      = excluded.regis_qty,
                reason         = excluded.reason,
                salesperson_id = excluded.salesperson_id,
                visit_id       = excluded.visit_id,
                registered_at  = excluded.registered_at;

        v_count := v_count + 1;
    end loop;

    return v_count;
end;
$$;

comment on function submit_posm_registration(jsonb) is
    'Registers POSM for an outlet, pending head office. InsertPosmRegis.';

revoke execute on function submit_posm_registration(jsonb) from public;
grant execute on function submit_posm_registration(jsonb) to authenticated;

-- -----------------------------------------------------------------------------
-- Handing it over, and taking it back
-- -----------------------------------------------------------------------------

create function submit_posm_movement(p_payload jsonb)
returns uuid
language plpgsql
security invoker
set search_path = public, pg_temp
as $$
declare
    v_id       uuid := (p_payload ->> 'id')::uuid;
    v_visit_id uuid := (p_payload ->> 'visit_id')::uuid;
    v_kind     posm_movement_kind := (p_payload ->> 'kind')::posm_movement_kind;
    v_sp_id    uuid := current_salesperson_id();
    v_customer uuid;
    v_line     jsonb;
    v_photo    jsonb;
    v_program  uuid;
    v_item     uuid;
    v_qty      integer;
    v_room     integer;
    v_photos   integer := 0;
    v_lines    integer := 0;
begin
    if v_id is null or v_visit_id is null then
        raise exception 'submit_posm_movement needs id and visit_id';
    end if;

    -- Already booked: a replay from the outbox.
    if exists (select 1 from posm_movement where id = v_id) then
        return v_id;
    end if;

    select v.customer_id into v_customer
    from visit v
    where v.id = v_visit_id and v.salesperson_id = v_sp_id;

    if v_customer is null then
        raise exception 'visit % is not a visit of this salesperson', v_visit_id;
    end if;

    insert into posm_movement (
        id, visit_id, customer_id, salesperson_id, kind, note, moved_on,
        client_created_at
    )
    values (
        v_id, v_visit_id, v_customer, v_sp_id, v_kind,
        nullif(btrim(p_payload ->> 'note'), ''),
        current_date,
        coalesce((p_payload ->> 'client_created_at')::timestamptz, now())
    );

    for v_line in select * from jsonb_array_elements(p_payload -> 'lines')
    loop
        v_program := (v_line ->> 'program_id')::uuid;
        v_item    := (v_line ->> 'posm_item_id')::uuid;
        v_qty     := coalesce((v_line ->> 'qty')::integer, 0);

        if v_qty < 1 then
            raise exception 'a movement line must move at least one';
        end if;

        if v_kind = 'delivery' then
            -- Only what head office approved and has not already been handed
            -- over. The registration's own check constraint would refuse the
            -- overshoot too; refusing here names the reason.
            select r.approved_qty - r.delivered_qty into v_room
            from posm_registration r
            where r.program_id = v_program
              and r.posm_item_id = v_item
              and r.customer_id = v_customer
              and r.status = 'approved';

            if not found then
                raise exception 'this POSM has no approved request for this outlet';
            end if;

            if v_qty > coalesce(v_room, 0) then
                raise exception
                    'only % of this POSM are left to deliver', coalesce(v_room, 0);
            end if;

            update posm_registration
            set delivered_qty = delivered_qty + v_qty
            where program_id = v_program
              and posm_item_id = v_item
              and customer_id = v_customer;

            insert into posm_placement (customer_id, program_id, posm_item_id, qty, placed_at)
            values (v_customer, v_program, v_item, v_qty, current_date)
            on conflict (customer_id, program_id, posm_item_id) do update
                set qty = posm_placement.qty + excluded.qty;
        else
            -- A recall takes back what is there, and no more. The allowance is
            -- not restored: the legacy books the recall as its own document and
            -- leaves AppQty alone, so a shop that gives a rack back needs a new
            -- ruling before it gets another.
            select pl.qty into v_room
            from posm_placement pl
            where pl.program_id = v_program
              and pl.posm_item_id = v_item
              and pl.customer_id = v_customer;

            if not found then
                raise exception 'this outlet does not hold that POSM';
            end if;

            if v_qty > coalesce(v_room, 0) then
                raise exception
                    'this outlet only holds % of that POSM', coalesce(v_room, 0);
            end if;

            if v_qty = v_room then
                delete from posm_placement
                where program_id = v_program
                  and posm_item_id = v_item
                  and customer_id = v_customer;
            else
                update posm_placement
                set qty = qty - v_qty
                where program_id = v_program
                  and posm_item_id = v_item
                  and customer_id = v_customer;
            end if;
        end if;

        insert into posm_movement_line (movement_id, program_id, posm_item_id, qty)
        values (v_id, v_program, v_item, v_qty);

        v_lines := v_lines + 1;
    end loop;

    if v_lines = 0 then
        raise exception 'a POSM movement must move something';
    end if;

    for v_photo in
        select * from jsonb_array_elements(coalesce(p_payload -> 'photos', '[]'::jsonb))
    loop
        -- Storage and the database are separate systems, so a path is checked to
        -- exist before a row claims it does.
        if not exists (
            select 1 from storage.objects o
            where o.bucket_id = 'visit-photos' and o.name = v_photo ->> 'storage_path'
        ) then
            raise exception 'photo % is not in storage', v_photo ->> 'storage_path';
        end if;

        insert into posm_movement_photo (
            movement_id, storage_path, taken_at, lat, lng, file_size
        )
        values (
            v_id,
            v_photo ->> 'storage_path',
            coalesce((v_photo ->> 'taken_at')::timestamptz, now()),
            (v_photo ->> 'lat')::double precision,
            (v_photo ->> 'lng')::double precision,
            (v_photo ->> 'file_size')::integer
        )
        on conflict (movement_id, storage_path) do nothing;

        v_photos := v_photos + 1;
    end loop;

    -- posm_image_mess_required. A rep saying they handed over a fridge is an
    -- assertion; saying it with a picture of the fridge in the shop is a record.
    if v_photos < 1 then
        raise exception 'a POSM handover needs at least one photo';
    end if;

    update posm_movement set photo_count = v_photos where id = v_id;

    return v_id;
end;
$$;

comment on function submit_posm_movement(jsonb) is
    'Delivers POSM to an outlet or recalls it. InsertDeliverPosm, order types IN and IR.';

revoke execute on function submit_posm_movement(jsonb) from public;
grant execute on function submit_posm_movement(jsonb) to authenticated;
