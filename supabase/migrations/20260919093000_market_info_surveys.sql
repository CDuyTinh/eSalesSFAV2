-- =============================================================================
-- Danh sách khảo sát thị trường
--
-- The step used to open a questionnaire. In the legacy it opens a *list*:
-- API_GetMarketInfo unions the competitor surveys and the market surveys live
-- for the branch today, marks each done or not, and lets the rep filter by kind
-- and by status before picking one. Several can be live at once and an outlet
-- owes all of them.
--
-- Two things follow. The questionnaire side gains what it never had — a date
-- window and a branch, the SI_SrvQuestionList columns the seed had no use for
-- while only one questionnaire could ever be active. And submit_survey stops
-- guessing which questionnaire it is being handed: with several live, picking
-- the first active one for the step would file answers under the wrong campaign.
-- =============================================================================

alter table survey_type
    add column if not exists from_date date,
    add column if not exists to_date   date,
    add column if not exists branch_id uuid references branch (id);

-- Null dates mean a questionnaire that is always live, which is what every
-- existing row is; the listing treats them that way rather than hiding them.
comment on column survey_type.from_date is
    'SI_SrvQuestionList.StartDate. Null runs the questionnaire indefinitely.';
comment on column survey_type.to_date is
    'SI_SrvQuestionList.EndDate. Null runs the questionnaire indefinitely.';
comment on column survey_type.branch_id is
    'SI_SrvQuestionCpny. Null runs the questionnaire in every branch.';

-- The old uniqueness said one questionnaire per step per code. That still holds,
-- but the note claiming only one may be active is no longer true, so the listing
-- below is what decides which are live.
comment on table survey_type is
    'A questionnaire. Several may be live for one step at once, each with its own window and branch, as SI_SrvQuestionList allows.';

-- -----------------------------------------------------------------------------
-- What this outlet owes today
-- -----------------------------------------------------------------------------

create function market_info_surveys(
    p_customer_id uuid,
    p_visit_id    uuid
)
returns table (
    kind         text,
    id           uuid,
    code         text,
    name         text,
    from_date    date,
    to_date      date,
    is_completed boolean,
    item_count   integer
)
language sql
stable
security invoker
set search_path = public, pg_temp
as $$
    with me as (
        select branch_id from customer where id = p_customer_id
    ),
    market as (
        select
            'market'::text as kind,
            t.id, t.code, t.name, t.from_date, t.to_date,
            exists (
                select 1 from survey s
                where s.visit_id = p_visit_id and s.survey_type_id = t.id
            ) as is_completed,
            (
                select count(*)::integer
                from survey_question q
                join survey_question_group g on g.id = q.group_id
                where g.survey_type_id = t.id
            ) as item_count
        from survey_type t
        cross join me
        where t.form_id = 'market_info'
          and t.is_active
          and (t.from_date is null or t.from_date <= current_date)
          and (t.to_date   is null or current_date <= t.to_date)
          and (t.branch_id is null or t.branch_id = me.branch_id)
    ),
    competitor as (
        select
            'competitor'::text as kind,
            c.id, c.code, c.name, c.from_date, c.to_date,
            -- Done means every required criterion answered for every pairing,
            -- which is how fs_GetStatusIsCompletedCompetitorSurvey counts it.
            not exists (
                select 1
                from competitor_survey_item i
                cross join competitor_survey_criteria sc
                where i.survey_id = c.id
                  and sc.survey_id = c.id
                  and sc.is_required
                  and not exists (
                      select 1 from competitor_survey_result r
                      where r.visit_id = p_visit_id
                        and r.survey_id = c.id
                        and r.product_id = i.product_id
                        and r.competitor_product_id = i.competitor_product_id
                        and r.criteria_id = sc.criteria_id
                  )
            ) as is_completed,
            (
                select count(*)::integer from competitor_survey_item i
                where i.survey_id = c.id
            ) as item_count
        from competitor_survey c
        cross join me
        where c.is_active
          and c.from_date <= current_date
          and current_date <= c.to_date
          and (c.branch_id is null or c.branch_id = me.branch_id)
    )
    select * from market
    union all
    select * from competitor
    order by is_completed, from_date desc nulls last, name;
$$;

comment on function market_info_surveys(uuid, uuid) is
    'Every market-information survey this outlet owes today, of both kinds, with whether this visit has finished it. The listing half of API_GetMarketInfo.';

-- -----------------------------------------------------------------------------
-- One competitor survey, ready to answer
-- -----------------------------------------------------------------------------

create function competitor_survey_detail(
    p_survey_id uuid,
    p_visit_id  uuid
)
returns jsonb
language sql
stable
security invoker
set search_path = public, pg_temp
as $$
    select jsonb_build_object(
        'id',   c.id,
        'code', c.code,
        'name', c.name,
        'criteria', coalesce((
            select jsonb_agg(jsonb_build_object(
                'id',          cr.id,
                'code',        cr.code,
                'name',        cr.name,
                'hint',        cr.hint,
                'is_required', sc.is_required
            ) order by sc.sort_order, cr.name)
            from competitor_survey_criteria sc
            join competitor_criteria cr on cr.id = sc.criteria_id
            where sc.survey_id = c.id
        ), '[]'::jsonb),
        'items', coalesce((
            select jsonb_agg(jsonb_build_object(
                'product_id',            p.id,
                'product_code',          p.code,
                'product_name',          p.name,
                'competitor_product_id', cp.id,
                'competitor_product',    cp.name,
                'competitor_id',         v.id,
                'competitor_name',       v.name,
                'answers', coalesce((
                    select jsonb_object_agg(r.criteria_id::text, jsonb_build_object(
                        'content', r.content,
                        'photos', coalesce((
                            select jsonb_agg(ph.storage_path order by ph.taken_at)
                            from competitor_survey_photo ph where ph.result_id = r.id
                        ), '[]'::jsonb)
                    ))
                    from competitor_survey_result r
                    where r.visit_id = p_visit_id
                      and r.survey_id = c.id
                      and r.product_id = i.product_id
                      and r.competitor_product_id = i.competitor_product_id
                ), '{}'::jsonb)
            ) order by i.sort_order, p.name, v.name)
            from competitor_survey_item i
            join product p on p.id = i.product_id
            join competitor_product cp on cp.id = i.competitor_product_id
            join competitor_vendor v on v.id = cp.vendor_id
            where i.survey_id = c.id
        ), '[]'::jsonb)
    )
    from competitor_survey c
    where c.id = p_survey_id;
$$;

comment on function competitor_survey_detail(uuid, uuid) is
    'One competitor survey with its criteria, its product pairings and whatever this visit has already answered. API_GetMarketInfoDetail type C.';

-- -----------------------------------------------------------------------------
-- Booking the answers
-- -----------------------------------------------------------------------------

create function submit_competitor_survey(p_payload jsonb)
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

    return v_count;
end;
$$;

comment on function submit_competitor_survey(jsonb) is
    'Files a competitor survey for one visit. InsertAnswersCompetitorSurvey.';
