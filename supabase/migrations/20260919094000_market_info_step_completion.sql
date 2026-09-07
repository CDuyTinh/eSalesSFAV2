-- =============================================================================
-- Khi nào bước thông tin thị trường mới xong
--
-- `submit_survey` marked the step finished the moment one questionnaire came
-- back, which was right while a step could only ever hold one. It no longer can:
-- an outlet owes every survey live for its branch today, of both kinds, and
-- finishing the first of three is not finishing the step.
--
-- Two changes, both narrow.
--
-- The questionnaire is now named in the payload rather than guessed. With one
-- active questionnaire per step `limit 1` was harmless; with several it would
-- file answers under whichever row came back first.
--
-- And market_info completes on the list rather than on the last thing submitted.
-- Every other step keeps the behaviour it had.
-- =============================================================================

create function refresh_market_info_step(p_visit_id uuid)
returns void
language plpgsql
security invoker
set search_path = public, pg_temp
as $$
declare
    v_customer    uuid;
    v_total       integer;
    v_done        integer;
begin
    select customer_id into v_customer from visit where id = p_visit_id;
    if v_customer is null then
        return;
    end if;

    select count(*), count(*) filter (where is_completed)
    into v_total, v_done
    from market_info_surveys(v_customer, p_visit_id);

    -- Nothing live counts as finished rather than stuck: an outlet whose branch
    -- is running no surveys today owes nothing, and the rep must be able to move
    -- on rather than stare at an empty list the step will not release. That falls
    -- out of the comparison below, where nought of nought is done.
    if v_done < v_total then
        delete from visit_step_result
        where visit_id = p_visit_id and form_id = 'market_info';
        return;
    end if;

    insert into visit_step_result (visit_id, form_id, completed_at, payload)
    values (
        p_visit_id,
        'market_info',
        now(),
        jsonb_build_object('surveys', v_total, 'completed', v_done)
    )
    on conflict (visit_id, form_id) do update
        set completed_at = excluded.completed_at,
            payload      = excluded.payload;
end;
$$;

comment on function refresh_market_info_step(uuid) is
    'Marks the market information step done only once every survey the outlet owes today has been answered on this visit.';

revoke execute on function refresh_market_info_step(uuid) from public;
grant execute on function refresh_market_info_step(uuid) to authenticated;

-- -----------------------------------------------------------------------------

create or replace function submit_survey(p_survey jsonb)
returns uuid
language plpgsql
security invoker
set search_path = public, pg_temp
as $$
declare
    v_survey_id uuid := (p_survey ->> 'id')::uuid;
    v_visit_id  uuid := (p_survey ->> 'visit_id')::uuid;
    v_form_id   text := p_survey ->> 'form_id';
    v_date      date := coalesce((p_survey ->> 'survey_date')::date, current_date);
    v_sp_id     uuid := current_salesperson_id();
    v_customer  uuid;
    v_type_id   uuid := (p_survey ->> 'survey_type_id')::uuid;
    v_pass      integer;
    v_total     integer;
    v_max       integer;
    v_missing   text;
begin
    if v_survey_id is null or v_visit_id is null or v_form_id is null then
        raise exception 'submit_survey needs id, visit_id and form_id';
    end if;

    -- Already booked: a replay from the outbox.
    if exists (select 1 from survey where id = v_survey_id) then
        return v_survey_id;
    end if;

    select v.customer_id into v_customer
    from visit v
    where v.id = v_visit_id and v.salesperson_id = v_sp_id;

    if v_customer is null then
        raise exception 'visit % is not an open visit of this salesperson', v_visit_id;
    end if;

    -- Named by the client where the client knows which one it opened; the older
    -- clients that send only a form_id still get the single active questionnaire.
    if v_type_id is not null then
        select t.id, t.pass_score into v_type_id, v_pass
        from survey_type t
        where t.id = v_type_id and t.form_id = v_form_id and t.is_active;
    else
        select t.id, t.pass_score into v_type_id, v_pass
        from survey_type t
        where t.form_id = v_form_id and t.is_active
        limit 1;
    end if;

    if v_type_id is null then
        raise exception 'no active questionnaire is configured for step %', v_form_id;
    end if;

    -- Every required question must be answered. Photo questions are exempt: the
    -- client cannot render them yet, and blocking on one would leave the rep unable
    -- to finish a step the app itself cannot complete — the same rule the workflow
    -- applies to steps it has no screen for.
    select q.code into v_missing
    from survey_question q
    join survey_question_group g on g.id = q.group_id
    where g.survey_type_id = v_type_id
      and q.is_required
      and q.answer_type <> 'photo'
      and not exists (
          select 1 from jsonb_array_elements(p_survey -> 'answers') as a
          where (a ->> 'question_id')::uuid = q.id
      )
    limit 1;

    if v_missing is not null then
        raise exception 'survey %: required question % was not answered', v_survey_id, v_missing;
    end if;

    -- Redoing the step replaces the earlier result, answers included by cascade.
    delete from survey where visit_id = v_visit_id and survey_type_id = v_type_id;

    insert into survey (
        id, survey_type_id, visit_id, customer_id, salesperson_id, survey_date,
        note, client_created_at
    )
    values (
        v_survey_id, v_type_id, v_visit_id, v_customer, v_sp_id, v_date,
        nullif(p_survey ->> 'note', ''),
        coalesce((p_survey ->> 'client_created_at')::timestamptz, now())
    );

    -- Scores come from the question and option definitions, never from the payload.
    insert into survey_answer (
        survey_id, question_id, option_id, answer_text, answer_value, answer_bool, score
    )
    select
        v_survey_id,
        q.id,
        o.id,
        nullif(a ->> 'answer_text', ''),
        (a ->> 'answer_value')::numeric,
        (a ->> 'answer_bool')::boolean,
        case q.answer_type
            when 'yes_no' then case when (a ->> 'answer_bool')::boolean then q.score else 0 end
            when 'single' then coalesce(o.score, 0)
            when 'multi'  then coalesce(o.score, 0)
            -- Answered at all is what scores; the value itself is data for whoever
            -- reports on it, not something this function can judge.
            when 'number' then case when (a ->> 'answer_value') is not null then q.score else 0 end
            when 'text'   then case when nullif(a ->> 'answer_text', '') is not null then q.score else 0 end
            else 0
        end
    from jsonb_array_elements(p_survey -> 'answers') as a
    join survey_question q on q.id = (a ->> 'question_id')::uuid
    join survey_question_group g on g.id = q.group_id and g.survey_type_id = v_type_id
    left join survey_question_option o
        on o.id = (a ->> 'option_id')::uuid and o.question_id = q.id;

    select coalesce(sum(score), 0) into v_total
    from survey_answer where survey_id = v_survey_id;

    -- The achievable total for this questionnaire as it stands today. Single takes
    -- its best option, multi can take them all, the rest are worth their question.
    select coalesce(sum(
        case q.answer_type
            when 'single' then (select coalesce(max(o.score), 0)
                                from survey_question_option o where o.question_id = q.id)
            when 'multi'  then (select coalesce(sum(o.score), 0)
                                from survey_question_option o where o.question_id = q.id)
            when 'photo'  then 0
            else q.score
        end
    ), 0) into v_max
    from survey_question q
    join survey_question_group g on g.id = q.group_id
    where g.survey_type_id = v_type_id;

    update survey
    set total_score = v_total,
        max_score   = v_max,
        is_passed   = v_total >= v_pass
    where id = v_survey_id;

    -- market_info holds a list, so one questionnaire coming back is not the step
    -- coming back. Every other step still finishes on its single result.
    if v_form_id = 'market_info' then
        perform refresh_market_info_step(v_visit_id);
    else
        insert into visit_step_result (visit_id, form_id, completed_at, payload)
        values (
            v_visit_id,
            v_form_id,
            now(),
            jsonb_build_object(
                'survey_id', v_survey_id,
                'total_score', v_total,
                'max_score', v_max,
                'is_passed', v_total >= v_pass
            )
        )
        on conflict (visit_id, form_id) do update
            set completed_at = excluded.completed_at,
                payload      = excluded.payload;
    end if;

    return v_survey_id;
end;
$$;

-- -----------------------------------------------------------------------------

create or replace function submit_competitor_survey(p_payload jsonb)
returns integer
language plpgsql
security invoker
set search_path = public, pg_temp
as $$
declare
    v_visit_id  uuid := (p_payload ->> 'visit_id')::uuid;
    v_survey_id uuid := (p_payload ->> 'survey_id')::uuid;
    v_taken_at  timestamptz := coalesce(
        (p_payload ->> 'client_created_at')::timestamptz, now()
    );
    v_sp_id     uuid := current_salesperson_id();
    v_customer  uuid;
    v_answer    jsonb;
    v_result_id uuid;
    v_photo     jsonb;
    v_photos    integer;
    v_count     integer := 0;
begin
    if v_visit_id is null or v_survey_id is null then
        raise exception 'submit_competitor_survey needs visit_id and survey_id';
    end if;

    select v.customer_id into v_customer
    from visit v
    where v.id = v_visit_id and v.salesperson_id = v_sp_id;

    if v_customer is null then
        raise exception 'visit % is not a visit of this salesperson', v_visit_id;
    end if;

    if not exists (
        select 1 from competitor_survey
        where id = v_survey_id
          and is_active
          and from_date <= current_date
          and current_date <= to_date
    ) then
        raise exception 'competitor survey % is not running today', v_survey_id;
    end if;

    for v_answer in select * from jsonb_array_elements(p_payload -> 'answers')
    loop
        -- A pairing that is not in the survey is refused rather than stored: an
        -- answer nobody asked for cannot be reported on, and accepting it would
        -- let a client invent rows against any product it liked.
        if not exists (
            select 1 from competitor_survey_item i
            where i.survey_id = v_survey_id
              and i.product_id = (v_answer ->> 'product_id')::uuid
              and i.competitor_product_id = (v_answer ->> 'competitor_product_id')::uuid
        ) then
            raise exception 'pairing is not part of survey %', v_survey_id;
        end if;

        if not exists (
            select 1 from competitor_survey_criteria sc
            where sc.survey_id = v_survey_id
              and sc.criteria_id = (v_answer ->> 'criteria_id')::uuid
        ) then
            raise exception 'criterion is not part of survey %', v_survey_id;
        end if;

        v_result_id := coalesce((v_answer ->> 'id')::uuid, gen_random_uuid());

        insert into competitor_survey_result (
            id, visit_id, customer_id, salesperson_id, survey_id,
            product_id, competitor_product_id, criteria_id,
            content, photo_count, client_created_at
        )
        values (
            v_result_id, v_visit_id, v_customer, v_sp_id, v_survey_id,
            (v_answer ->> 'product_id')::uuid,
            (v_answer ->> 'competitor_product_id')::uuid,
            (v_answer ->> 'criteria_id')::uuid,
            coalesce(v_answer ->> 'content', ''),
            0,
            v_taken_at
        )
        on conflict (visit_id, survey_id, product_id, competitor_product_id, criteria_id)
        do update set content = excluded.content
        returning id into v_result_id;

        -- Photos are replaced wholesale with what the client is holding. A rep who
        -- deleted a shot before syncing meant to delete it.
        delete from competitor_survey_photo where result_id = v_result_id;

        v_photos := 0;
        for v_photo in
            select * from jsonb_array_elements(coalesce(v_answer -> 'photos', '[]'::jsonb))
        loop
            insert into competitor_survey_photo (
                result_id, storage_path, taken_at, lat, lng, file_size
            )
            values (
                v_result_id,
                v_photo ->> 'storage_path',
                coalesce((v_photo ->> 'taken_at')::timestamptz, v_taken_at),
                (v_photo ->> 'lat')::double precision,
                (v_photo ->> 'lng')::double precision,
                (v_photo ->> 'file_size')::integer
            )
            on conflict (result_id, storage_path) do nothing;
            v_photos := v_photos + 1;
        end loop;

        update competitor_survey_result
        set photo_count = v_photos
        where id = v_result_id;

        v_count := v_count + 1;
    end loop;

    perform refresh_market_info_step(v_visit_id);

    return v_count;
end;
$$;

revoke execute on function submit_competitor_survey(jsonb) from public;
grant execute on function submit_competitor_survey(jsonb) to authenticated;
