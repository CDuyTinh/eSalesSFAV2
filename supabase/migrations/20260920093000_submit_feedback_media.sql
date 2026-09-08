-- =============================================================================
-- submit_feedback nhận ảnh và nhiều bản ghi âm
--
-- Same shape as the display and POSM submits: the media is already in storage by
-- the time this runs, so every path is checked to exist before a row claims it
-- does. The select policies limit that check to the rep's own folder, which means
-- a path belonging to anyone else reads as missing rather than as someone else's.
--
-- The caps come from the step's own config, so the client refuses exactly what
-- the server refuses instead of discovering a limit from a rejection.
-- =============================================================================

create or replace function submit_feedback(p_feedback jsonb)
returns uuid
language plpgsql
security invoker
set search_path = public, pg_temp
as $$
declare
    v_id        uuid    := (p_feedback ->> 'id')::uuid;
    v_visit_id  uuid    := (p_feedback ->> 'visit_id')::uuid;
    v_date      date    := coalesce((p_feedback ->> 'feedback_date')::date, current_date);
    v_sp_id     uuid    := current_salesperson_id();
    v_customer  uuid;
    v_note      text    := nullif(btrim(p_feedback ->> 'note'), '');
    v_topic     uuid    := (p_feedback ->> 'topic_id')::uuid;
    v_config    jsonb;
    v_min       integer;
    v_photo_min integer;
    v_photo_max integer;
    v_clip_max  integer;
    v_total_max integer;
    v_photos    jsonb   := coalesce(p_feedback -> 'photos', '[]'::jsonb);
    v_audios    jsonb   := coalesce(p_feedback -> 'audios', '[]'::jsonb);
    v_item      jsonb;
    v_path      text;
    v_seconds   integer;
    v_total     integer := 0;
    v_order     integer := 0;
begin
    if v_id is null or v_visit_id is null then
        raise exception 'submit_feedback needs both id and visit_id';
    end if;

    -- Already booked: a replay from the outbox.
    if exists (select 1 from visit_feedback where id = v_id) then
        return v_id;
    end if;

    select v.customer_id into v_customer
    from visit v
    where v.id = v_visit_id and v.salesperson_id = v_sp_id;

    if v_customer is null then
        raise exception 'visit % is not an open visit of this salesperson', v_visit_id;
    end if;

    select s.config into v_config
    from sales_step s
    where s.form_id = 'feedback';

    v_config    := coalesce(v_config, '{}'::jsonb);
    v_min       := coalesce((v_config ->> 'note_min_length')::integer, 1);
    v_photo_min := coalesce((v_config ->> 'photo_min')::integer, 0);
    v_photo_max := coalesce((v_config ->> 'photo_max')::integer, 5);
    v_clip_max  := coalesce((v_config ->> 'audio_max_seconds')::integer, 300);
    v_total_max := coalesce((v_config ->> 'audio_total_seconds')::integer, 900);

    -- The step exists to record something, and an empty note records nothing.
    -- The legacy accepts one; see the note in the previous migration for why this
    -- does not.
    if v_note is null or char_length(v_note) < v_min then
        raise exception
            'feedback %: note is shorter than the % characters this step requires',
            v_id, v_min;
    end if;

    -- A topic the rep could not have been shown is a topic they did not choose, and
    -- filing feedback under a GPS reason code would poison the one index that makes
    -- this table useful.
    if v_topic is not null and not exists (
        select 1 from reason_code r
        where r.id = v_topic and r.kind = 'feedback_topic'
    ) then
        raise exception 'feedback %: % is not a feedback topic', v_id, v_topic;
    end if;

    if jsonb_array_length(v_photos) < v_photo_min then
        raise exception
            'feedback %: this step wants at least % photo(s)', v_id, v_photo_min;
    end if;

    if jsonb_array_length(v_photos) > v_photo_max then
        raise exception
            'feedback %: this step allows at most % photo(s)', v_id, v_photo_max;
    end if;

    delete from visit_feedback where visit_id = v_visit_id;

    insert into visit_feedback (
        id, visit_id, customer_id, salesperson_id, feedback_date,
        topic_id, note, client_created_at
    )
    values (
        v_id, v_visit_id, v_customer, v_sp_id, v_date,
        v_topic, v_note,
        coalesce((p_feedback ->> 'client_created_at')::timestamptz, now())
    );

    for v_item in select * from jsonb_array_elements(v_photos)
    loop
        v_path := v_item ->> 'storage_path';

        if not exists (
            select 1 from storage.objects o
            where o.bucket_id = 'visit-photos' and o.name = v_path
        ) then
            raise exception 'feedback %: photo % is not in storage', v_id, v_path;
        end if;

        insert into visit_feedback_photo (
            feedback_id, storage_path, taken_at, lat, lng, file_size
        )
        values (
            v_id, v_path,
            coalesce((v_item ->> 'taken_at')::timestamptz, now()),
            (v_item ->> 'lat')::double precision,
            (v_item ->> 'lng')::double precision,
            (v_item ->> 'file_size')::integer
        )
        on conflict (feedback_id, storage_path) do nothing;
    end loop;

    for v_item in select * from jsonb_array_elements(v_audios)
    loop
        v_path    := v_item ->> 'storage_path';
        v_seconds := coalesce((v_item ->> 'seconds')::integer, 0);

        if not exists (
            select 1 from storage.objects o
            where o.bucket_id = 'visit-audio' and o.name = v_path
        ) then
            raise exception 'feedback %: recording % is not in storage', v_id, v_path;
        end if;

        -- Per clip, as SALES_RECORD_TIME_FILE is. A client that splits at the cap
        -- sends several clips; one that sends a single longer file is refused
        -- rather than quietly stored past a limit head office set.
        if v_seconds > v_clip_max then
            raise exception
                'feedback %: a recording may run %s seconds at most', v_id, v_clip_max;
        end if;

        v_total := v_total + v_seconds;
        v_order := v_order + 1;

        insert into visit_feedback_audio (
            feedback_id, storage_path, seconds, sort_order, recorded_at, file_size
        )
        values (
            v_id, v_path, greatest(v_seconds, 1), v_order,
            coalesce((v_item ->> 'recorded_at')::timestamptz, now()),
            (v_item ->> 'file_size')::integer
        )
        on conflict (feedback_id, storage_path) do nothing;
    end loop;

    if v_total > v_total_max then
        raise exception
            'feedback %: the recordings run longer than the %s seconds allowed',
            v_id, v_total_max;
    end if;

    update visit_feedback
    set photo_count   = jsonb_array_length(v_photos),
        audio_count   = v_order,
        audio_seconds = v_total
    where id = v_id;

    insert into visit_step_result (visit_id, form_id, completed_at, payload)
    values (
        v_visit_id,
        'feedback',
        now(),
        jsonb_build_object(
            'feedback_id', v_id,
            'topic_id', v_topic,
            'photos', jsonb_array_length(v_photos),
            'recordings', v_order
        )
    )
    on conflict (visit_id, form_id) do update
        set completed_at = excluded.completed_at,
            payload      = excluded.payload;

    return v_id;
end;
$$;

revoke execute on function submit_feedback(jsonb) from public;
grant execute on function submit_feedback(jsonb) to authenticated;

comment on function submit_feedback(jsonb) is
    'Files one customer feedback for a visit, with its photos and recordings. InsertFeedBackCustomer.';
