-- =============================================================================
-- Surveys
--
-- Backs `posm_status` and `market_info`, and any questionnaire step added after
-- them. Legacy survey_types / survey_question_groups / survey_questions /
-- survey_question_options / surveys / survey_answers.
--
-- One engine, two steps. The join that makes that work is `survey_type.form_id`:
-- each questionnaire names the workflow step it belongs to, so the client renders
-- whichever one the step it opened points at. Adding a third questionnaire step is
-- then a row here plus a form id in `sales_step` — the same property the workflow
-- has had since the first slice, one level further down.
--
-- Scores are recomputed here rather than accepted from the device, for the reason
-- order prices are: a client that can name its own score is a client that can pass
-- a Perfect Store audit it failed. The payload carries what the rep chose; this
-- decides what it was worth.
--
-- `max_score` is stored alongside the total rather than derived on read. A
-- questionnaire that gains a question next month would otherwise silently change
-- the denominator of every audit already taken.
-- =============================================================================

create type survey_answer_type as enum (
    'yes_no',
    'single',   -- one option
    'multi',    -- any number of options
    'number',
    'text',
    'photo'     -- defined, not yet rendered; see the note in :domain
);

create table survey_type (
    id         uuid primary key default gen_random_uuid(),
    code       text not null unique,
    name       text not null,

    -- The workflow step this questionnaire belongs to. This is what lets one screen
    -- serve every questionnaire step.
    form_id    text not null references sales_step (form_id),

    -- Total needed to pass. Zero means the survey is informational and always
    -- passes, which is the right default for something like market_info.
    pass_score integer not null default 0,

    is_active  boolean not null default true,

    -- One active questionnaire per step at a time; two would leave the client
    -- choosing arbitrarily between them.
    unique (form_id, code)
);

create index on survey_type (form_id);

create table survey_question_group (
    id             uuid    primary key default gen_random_uuid(),
    survey_type_id uuid    not null references survey_type (id) on delete cascade,
    name           text    not null,
    sort_order     integer not null default 0
);

create index on survey_question_group (survey_type_id);

create table survey_question (
    id          uuid    primary key default gen_random_uuid(),
    group_id    uuid    not null references survey_question_group (id) on delete cascade,
    code        text    not null,
    content     text    not null,
    answer_type survey_answer_type not null,
    is_required boolean not null default true,

    -- What a full answer is worth. For single/multi the option scores govern
    -- instead; this is the value of yes_no, number and text.
    score       integer not null default 0
        constraint survey_question_score_not_negative check (score >= 0),

    sort_order  integer not null default 0,

    unique (group_id, code)
);

create index on survey_question (group_id);

create table survey_question_option (
    id          uuid    primary key default gen_random_uuid(),
    question_id uuid    not null references survey_question (id) on delete cascade,
    code        text    not null,
    content     text    not null,
    score       integer not null default 0
        constraint survey_option_score_not_negative check (score >= 0),
    sort_order  integer not null default 0,

    unique (question_id, code)
);

create index on survey_question_option (question_id);

-- -----------------------------------------------------------------------------
-- Results
-- -----------------------------------------------------------------------------

create table survey (
    id                uuid    primary key,
    survey_type_id    uuid    not null references survey_type (id),
    visit_id          uuid    not null references visit (id) on delete cascade,
    customer_id       uuid    not null references customer (id),
    salesperson_id    uuid    not null references salesperson (id),
    survey_date       date    not null,

    total_score       integer not null default 0,
    max_score         integer not null default 0,
    is_passed         boolean not null default false,

    note              text,
    client_created_at timestamptz not null,
    created_at        timestamptz not null default now(),
    updated_at        timestamptz not null default now(),

    -- One result per questionnaire per visit. Redoing the step replaces it rather
    -- than leaving two scores with no way to tell which counts.
    unique (visit_id, survey_type_id)
);

create index on survey (customer_id, survey_date desc);
create index on survey (salesperson_id, survey_date desc);

create table survey_answer (
    id           uuid    primary key default gen_random_uuid(),
    survey_id    uuid    not null references survey (id) on delete cascade,
    question_id  uuid    not null references survey_question (id),

    -- Exactly one of these carries the answer, depending on the question's type.
    -- Kept as separate typed columns rather than one text column so a number stays
    -- a number for whoever reports on it later.
    option_id    uuid    references survey_question_option (id),
    answer_text  text,
    answer_value numeric(18, 3),
    answer_bool  boolean,

    score        integer not null default 0,

    -- A multi question contributes one row per chosen option, so the key includes
    -- the option. Coalesced because every other type has no option.
    unique (survey_id, question_id, option_id)
);

create index on survey_answer (survey_id);

create trigger survey_set_updated_at
    before update on survey
    for each row execute function set_updated_at();

-- -----------------------------------------------------------------------------
-- submit_survey
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
    v_type_id   uuid;
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

    select t.id, t.pass_score into v_type_id, v_pass
    from survey_type t
    where t.form_id = v_form_id and t.is_active
    limit 1;

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

    return v_survey_id;
end;
$$;

revoke execute on function submit_survey(jsonb) from public;
grant execute on function submit_survey(jsonb) to authenticated;

-- -----------------------------------------------------------------------------
-- Row Level Security
-- -----------------------------------------------------------------------------

alter table survey_type            enable row level security;
alter table survey_question_group  enable row level security;
alter table survey_question        enable row level security;
alter table survey_question_option enable row level security;
alter table survey                 enable row level security;
alter table survey_answer          enable row level security;

-- The questionnaire itself is reference data: every rep reads it, no rep writes it.
create policy "reference readable by authenticated"
    on survey_type for select to authenticated using (true);
create policy "reference readable by authenticated"
    on survey_question_group for select to authenticated using (true);
create policy "reference readable by authenticated"
    on survey_question for select to authenticated using (true);
create policy "reference readable by authenticated"
    on survey_question_option for select to authenticated using (true);

create policy "rep reads own surveys"
    on survey for select to authenticated
    using (salesperson_id = current_salesperson_id());

create policy "rep writes own surveys"
    on survey for insert to authenticated
    with check (salesperson_id = current_salesperson_id());

-- Scores are written by submit_survey in the same transaction as the insert.
create policy "rep updates own surveys"
    on survey for update to authenticated
    using (salesperson_id = current_salesperson_id())
    with check (salesperson_id = current_salesperson_id());

-- Redoing the step deletes the visit's earlier result.
create policy "rep deletes own surveys"
    on survey for delete to authenticated
    using (salesperson_id = current_salesperson_id());

create policy "rep reads own survey answers"
    on survey_answer for select to authenticated
    using (
        exists (
            select 1 from survey s
            where s.id = survey_answer.survey_id
              and s.salesperson_id = current_salesperson_id()
        )
    );

create policy "rep writes own survey answers"
    on survey_answer for insert to authenticated
    with check (
        exists (
            select 1 from survey s
            where s.id = survey_answer.survey_id
              and s.salesperson_id = current_salesperson_id()
        )
    );
