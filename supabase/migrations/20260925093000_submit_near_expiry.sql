-- =============================================================================
-- submit_near_expiry_check — InsertTransDate
--
-- The document, its lots and its evidence in one transaction, with the two rules
-- the legacy form enforces on the device and nothing enforces on the server:
-- a lot number of the configured length, and a photograph or a reason for its
-- absence.
--
-- Enforced here as well as on the client on purpose. The client's copy of a rule
-- is a courtesy to the rep — it tells them before they walk away — and the
-- server's copy is what makes the rule true.
-- =============================================================================

create function submit_near_expiry_check(p_check jsonb)
returns uuid
language plpgsql
security invoker
set search_path = public, pg_temp
as $$
declare
    v_id        uuid    := (p_check ->> 'id')::uuid;
    v_visit_id  uuid    := (p_check ->> 'visit_id')::uuid;
    v_sp_id     uuid    := current_salesperson_id();
    v_customer  uuid;
    v_reason    uuid    := (p_check ->> 'no_photo_reason_id')::uuid;
    v_len       integer;
    v_lot       jsonb;
    v_photo     jsonb;
    v_lot_no    text;
    v_qty       integer;
    v_base      integer;
    v_lots      integer := 0;
    v_total     integer := 0;
    v_photos    integer := 0;
begin
    if v_id is null or v_visit_id is null then
        raise exception 'submit_near_expiry_check needs both id and visit_id';
    end if;

    -- Already booked: a replay from the outbox.
    if exists (select 1 from near_expiry_check where id = v_id) then
        return v_id;
    end if;

    select v.customer_id into v_customer
    from visit v
    where v.id = v_visit_id and v.salesperson_id = v_sp_id;

    if v_customer is null then
        raise exception 'visit % is not an open visit of this salesperson', v_visit_id;
    end if;

    select coalesce((s.config ->> 'lot_no_length')::integer, 8) into v_len
    from sales_step s
    where s.form_id = 'stock_out_date';

    v_len := coalesce(v_len, 8);

    -- A reason the rep could not have been shown is a reason they did not pick.
    if v_reason is not null and not exists (
        select 1 from reason_code r
        where r.id = v_reason and r.kind = 'photo_skipped'
    ) then
        raise exception 'that is not a reason for skipping a photo';
    end if;

    -- Redoing the step replaces the earlier document, lots and photos included
    -- by cascade.
    delete from near_expiry_check where visit_id = v_visit_id;

    insert into near_expiry_check (
        id, visit_id, customer_id, salesperson_id, check_date,
        note, no_photo_reason_id, lat, lng, client_created_at
    )
    values (
        v_id, v_visit_id, v_customer, v_sp_id,
        coalesce((p_check ->> 'check_date')::date, current_date),
        nullif(btrim(p_check ->> 'note'), ''),
        v_reason,
        (p_check ->> 'lat')::double precision,
        (p_check ->> 'lng')::double precision,
        coalesce((p_check ->> 'client_created_at')::timestamptz, now())
    );

    for v_lot in select * from jsonb_array_elements(coalesce(p_check -> 'lots', '[]'::jsonb))
    loop
        v_lot_no := btrim(v_lot ->> 'lot_no');
        v_qty    := coalesce((v_lot ->> 'qty')::integer, 0);
        v_base   := coalesce((v_lot ->> 'base_qty')::integer, 0);

        -- `input_lot_num_then_eght`. A rep who types seven has misread the case.
        if v_lot_no is null or char_length(v_lot_no) <> v_len then
            raise exception 'a lot number is % characters', v_len;
        end if;

        if v_qty < 1 or v_base < 1 then
            raise exception 'lot %: a quantity must be at least one', v_lot_no;
        end if;

        -- The unique index would refuse this too; refusing here names the lot.
        if exists (
            select 1 from near_expiry_lot l
            where l.check_id = v_id
              and l.product_id = (v_lot ->> 'product_id')::uuid
              and l.lot_no = v_lot_no
        ) then
            raise exception 'lot % is entered twice for the same product', v_lot_no;
        end if;

        insert into near_expiry_lot (
            check_id, product_id, lot_no, expiry_date, uom_code, qty, base_qty
        )
        values (
            v_id,
            (v_lot ->> 'product_id')::uuid,
            v_lot_no,
            (v_lot ->> 'expiry_date')::date,
            v_lot ->> 'uom_code',
            v_qty,
            v_base
        );

        v_lots  := v_lots + 1;
        v_total := v_total + v_base;
    end loop;

    if v_lots = 0 then
        raise exception 'a near-expiry check must record at least one lot';
    end if;

    for v_photo in
        select * from jsonb_array_elements(coalesce(p_check -> 'photos', '[]'::jsonb))
    loop
        -- Storage and the database are separate systems, so a path is checked to
        -- exist before a row claims it does.
        if not exists (
            select 1 from storage.objects o
            where o.bucket_id = 'visit-photos' and o.name = v_photo ->> 'storage_path'
        ) then
            raise exception 'photo % is not in storage', v_photo ->> 'storage_path';
        end if;

        insert into near_expiry_photo (
            check_id, storage_path, taken_at, lat, lng, file_size
        )
        values (
            v_id,
            v_photo ->> 'storage_path',
            coalesce((v_photo ->> 'taken_at')::timestamptz, now()),
            (v_photo ->> 'lat')::double precision,
            (v_photo ->> 'lng')::double precision,
            (v_photo ->> 'file_size')::integer
        )
        on conflict (check_id, storage_path) do nothing;

        v_photos := v_photos + 1;
    end loop;

    -- `alert_chosen_reason`: evidence, or an explanation of its absence, never
    -- neither. A photograph makes the reason meaningless, so it is cleared
    -- rather than left contradicting the picture beside it.
    if v_photos = 0 and v_reason is null then
        raise exception
            'a near-expiry check needs a photo, or a reason for not taking one';
    end if;

    update near_expiry_check
    set lot_count          = v_lots,
        total_qty          = v_total,
        photo_count        = v_photos,
        no_photo_reason_id = case when v_photos > 0 then null else v_reason end
    where id = v_id;

    insert into visit_step_result (visit_id, form_id, completed_at, payload)
    values (
        v_visit_id,
        'stock_out_date',
        now(),
        jsonb_build_object(
            'check_id', v_id,
            'lots', v_lots,
            'total_qty', v_total,
            'photos', v_photos
        )
    )
    on conflict (visit_id, form_id) do update
        set completed_at = excluded.completed_at,
            payload      = excluded.payload;

    return v_id;
end;
$$;

comment on function submit_near_expiry_check(jsonb) is
    'Files the near-expiry stock found on one call, lot by lot. InsertTransDate.';

revoke execute on function submit_near_expiry_check(jsonb) from public;
grant execute on function submit_near_expiry_check(jsonb) to authenticated;

-- -----------------------------------------------------------------------------
-- What this visit already recorded
--
-- So reopening the step shows the lots the rep entered rather than a blank list
-- they would key in twice.
-- -----------------------------------------------------------------------------

create function near_expiry_for(p_visit_id uuid)
returns jsonb
language sql
stable
security invoker
set search_path = public, pg_temp
as $$
    select jsonb_build_object(
        'check_id', c.id,
        'note', c.note,
        'no_photo_reason_id', c.no_photo_reason_id,
        'lot_count', c.lot_count,
        'total_qty', c.total_qty,
        'photo_count', c.photo_count,
        'lots', coalesce((
            select jsonb_agg(jsonb_build_object(
                'product_id', p.id,
                'product_code', p.code,
                'product_name', p.name,
                'lot_no', l.lot_no,
                'expiry_date', l.expiry_date,
                'uom_code', l.uom_code,
                'qty', l.qty,
                'base_qty', l.base_qty
            ) order by p.name, l.expiry_date, l.lot_no)
            from near_expiry_lot l
            join product p on p.id = l.product_id
            where l.check_id = c.id
        ), '[]'::jsonb)
    )
    from near_expiry_check c
    where c.visit_id = p_visit_id;
$$;

comment on function near_expiry_for(uuid) is
    'The near-expiry document this visit already filed, if any, with its lots.';
