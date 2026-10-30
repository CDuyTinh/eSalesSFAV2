-- =============================================================================
-- eSalesManager — EDAC field coaching (Huấn luyện EDAC)
--
-- Legacy: PPC_EDACTraining (one coaching day), PPC_EDACTrainingProgress (every
-- answer), PPC_EDACTrainingResult (closing note and photos), and the criteria
-- in OM_WorkWithCriteriaEDAC / …HeaderEDAC / …DetailEDAC, all driven by
-- EDACTrainingQuery and the Save*/Start* handlers.
--
-- A coaching day is a work plan of the supervisor's own, of a field-training
-- category (WW-COACH), naming the rep as its object — exactly as the legacy
-- hung PPC_EDACTraining off OM_MakingPlan. The supervisor walks eight steps:
--
--   0 Chuẩn bị            plan-level   scale / text criteria (legacy PSE)
--   1 Quan sát            per outlet   yes/no on the 8 selling steps (OTE)
--   2 Giải thích          plan-level   scale criteria, after a summary of 1 (ESE)
--   3 Làm mẫu             per outlet   a note on what was demonstrated
--   4 Áp dụng             per outlet   yes/no on the 8 selling steps (ASE)
--   5 Đánh giá            per outlet   scale criteria (CSE); open alongside 4
--   6 Đánh giá cuối ngày  plan-level   scale criteria against the day's KPIs (DBRE)
--   7 Hoàn tất            result text + a photo of each, closes the plan
--
-- current_step is the first step not yet finished, as in the legacy. A step
-- may be saved any number of times; "finishing" it moves current_step on.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- Criteria
-- -----------------------------------------------------------------------------

create table edac_criterion (
    id          uuid     primary key default gen_random_uuid(),
    step        smallint not null check (step in (0, 1, 2, 4, 5, 6)),
    code        text     not null,
    content     text     not null,
    /** scale = pick one option (legacy M), yes_no (Y), text (T). */
    kind        text     not null check (kind in ('scale', 'yes_no', 'text')),
    /** Legacy KPI tag on end-of-day criteria: SOA revenue, ASO, PC orders. */
    kpi         text,
    is_required boolean  not null default true,
    sort_order  integer  not null default 0,
    is_active   boolean  not null default true,
    unique (step, code)
);

create table edac_criterion_option (
    criterion_id uuid     not null references edac_criterion (id) on delete cascade,
    value        smallint not null,
    label        text     not null,
    primary key (criterion_id, value)
);

-- -----------------------------------------------------------------------------
-- Coaching days and their answers
-- -----------------------------------------------------------------------------

create table edac_training (
    id                uuid        primary key default gen_random_uuid(),
    plan_id           uuid        not null unique references work_plan (id) on delete cascade,
    supervisor_id     uuid        not null references salesperson (id),
    salesperson_id    uuid        not null references salesperson (id),
    branch_id         uuid        not null references branch (id),
    training_date     date        not null,
    current_step      smallint    not null default 0 check (current_step between 0 and 7),
    status            text        not null default 'in_progress' check (status in ('in_progress', 'completed')),
    check_in_at       timestamptz not null default now(),
    check_out_at      timestamptz,
    result            text,
    /** visit-photos paths, `<supervisor_id>/edac-<training_id>/<file>`. */
    supervisor_photo  text,
    salesperson_photo text,
    created_at        timestamptz not null default now(),
    updated_at        timestamptz not null default now()
);

create index on edac_training (supervisor_id, training_date);
create index on edac_training (salesperson_id, training_date);

create table edac_answer (
    id            uuid        primary key default gen_random_uuid(),
    training_id   uuid        not null references edac_training (id) on delete cascade,
    step          smallint    not null,
    /** Set on the per-outlet steps (1, 4, 5), null on the plan-level ones. */
    customer_id   uuid        references customer (id),
    criterion_id  uuid        not null references edac_criterion (id),
    option_value  smallint,
    yes_no        boolean,
    text_value    text,
    note          text,
    answered_at   timestamptz not null default now()
);

create unique index edac_answer_once
    on edac_answer (training_id, criterion_id, coalesce(customer_id, '00000000-0000-0000-0000-000000000000'::uuid));

/** Step 3 (Làm mẫu) records what was shown at each outlet, not a score. */
create table edac_customer_note (
    training_id uuid        not null references edac_training (id) on delete cascade,
    customer_id uuid        not null references customer (id),
    step        smallint    not null,
    note        text        not null,
    noted_at    timestamptz not null default now(),
    primary key (training_id, customer_id, step)
);

-- Read and written only through the functions below.
alter table edac_criterion        enable row level security;
alter table edac_criterion_option enable row level security;
alter table edac_training         enable row level security;
alter table edac_answer           enable row level security;
alter table edac_customer_note    enable row level security;

-- -----------------------------------------------------------------------------
-- Criteria seed, from the legacy OM_WorkWithCriteria*EDAC rows
-- -----------------------------------------------------------------------------

do $$
declare
    v_id uuid;
    v_selling_steps text[] := array[
        'Check-in tại cửa hàng, chấm định vị GPS',
        'Chào hỏi chủ cửa hàng',
        'Giới thiệu thông tin bán hàng',
        'Ghi nhận đơn hàng',
        'Chốt đơn hàng',
        'Kiểm tồn',
        'Sắp xếp trưng bày',
        'Chào hỏi rời khỏi cửa hàng'];
    v_scale5 text[] := array[
        'Thường không đạt kết quả',
        'Đạt một số kết quả, nhưng do tập thể, may mắn',
        'Đạt kết quả do nỗ lực cá nhân, nhưng chưa đều',
        'Thường xuyên hoàn thành tốt nhiệm vụ được giao',
        'Thường xuyên vượt chỉ tiêu công việc được giao'];
    v_steps5 text[] := array[
        'Thiếu 3 bước bán hàng',
        'Thiếu 2 bước bán hàng',
        'Thiếu 1 bước bán hàng',
        'Đủ các bước bán hàng nhưng một vài bước cần cải thiện',
        'Đúng và đủ các bước bán hàng'];
    v_relation text[] := array['Chưa tốt', 'Tốt', 'Xuất sắc'];
    v_display text[] := array['Chưa hoàn thành', 'Hoàn thành chưa đủ', 'Hoàn thành', 'Hoàn thành xuất sắc'];
    v_target5 text[] := array['Chưa đạt', 'Chưa đạt nhưng gần với chỉ tiêu', 'Trung bình', 'Đạt', 'Vượt'];
    i int;
begin
    -- 0 Chuẩn bị
    insert into edac_criterion (step, code, content, kind, sort_order)
    values (0, '00001', 'Kết quả bán hàng quá khứ', 'scale', 1) returning id into v_id;
    insert into edac_criterion_option select v_id, n, v_scale5[n] from generate_series(1, 5) n;
    insert into edac_criterion (step, code, content, kind, sort_order)
    values (0, '00002', 'Kiến thức về tuyến bán hàng MCP', 'text', 2);

    -- 1 Quan sát, 4 Áp dụng: the eight selling steps, yes / no
    foreach i in array array[1, 4] loop
        for n in 1 .. 8 loop
            insert into edac_criterion (step, code, content, kind, sort_order)
            values (i, lpad(n::text, 5, '0'), 'Bước ' || n || ' – ' || v_selling_steps[n], 'yes_no', n);
        end loop;
    end loop;

    -- 2 Giải thích, 5 Đánh giá: the same three scales
    foreach i in array array[2, 5] loop
        insert into edac_criterion (step, code, content, kind, sort_order)
        values (i, '00001', 'Thực hiện đầy đủ các bước bán hàng', 'scale', 1) returning id into v_id;
        insert into edac_criterion_option select v_id, n, v_steps5[n] from generate_series(1, 5) n;
        insert into edac_criterion (step, code, content, kind, sort_order)
        values (i, '00002', 'Quan hệ với khách hàng', 'scale', 2) returning id into v_id;
        insert into edac_criterion_option select v_id, n, v_relation[n] from generate_series(1, 3) n;
        insert into edac_criterion (step, code, content, kind, sort_order)
        values (i, '00003', 'Kết quả trưng bày', 'scale', 3) returning id into v_id;
        insert into edac_criterion_option select v_id, n, v_display[n] from generate_series(1, 4) n;
    end loop;

    -- 6 Đánh giá cuối ngày
    insert into edac_criterion (step, code, content, kind, kpi, sort_order)
    values (6, '00001', 'Doanh số', 'scale', 'SOA', 1) returning id into v_id;
    insert into edac_criterion_option select v_id, n, v_target5[n] from generate_series(1, 5) n;
    insert into edac_criterion (step, code, content, kind, kpi, sort_order)
    values (6, '00002', 'SKU/đơn (ASO)', 'scale', 'ASO', 2) returning id into v_id;
    insert into edac_criterion_option select v_id, n, v_scale5[n] from generate_series(1, 5) n;
    insert into edac_criterion (step, code, content, kind, kpi, sort_order)
    values (6, '00003', 'Đơn hàng', 'scale', 'PC', 3) returning id into v_id;
    insert into edac_criterion_option select v_id, n, v_scale5[n] from generate_series(1, 5) n;
end;
$$;

-- -----------------------------------------------------------------------------
-- Helpers
-- -----------------------------------------------------------------------------

/** The caller's own coaching day, or a refusal. Internal. */
create or replace function edac_training_of(p_id uuid)
returns edac_training
language plpgsql
stable
security definer
set search_path = public, pg_temp
as $$
declare
    v edac_training;
begin
    select * into v from edac_training where id = p_id and supervisor_id = current_salesperson_id();
    if not found then
        raise exception 'không tìm thấy buổi huấn luyện của bạn';
    end if;
    return v;
end;
$$;

/** Whether the outlet is on one of the rep's active routes. Internal. */
create or replace function edac_rep_outlet(p_rep uuid, p_customer uuid)
returns boolean
language sql
stable
security definer
set search_path = public, pg_temp
as $$
    select exists (
        select 1 from route_customer rc
        join sales_route r on r.id = rc.sales_route_id and r.is_active
        where rc.customer_id = p_customer and rc.is_active and r.salesperson_id = p_rep
    );
$$;

create or replace function edac_is_outlet_step(p_step smallint)
returns boolean
language sql
immutable
as $$ select p_step in (1, 3, 4, 5); $$;

/** Whether every required, active criterion of the step has an answer. Internal. */
create or replace function edac_step_answered(p_training uuid, p_step smallint, p_customer uuid)
returns boolean
language sql
stable
security definer
set search_path = public, pg_temp
as $$
    select not exists (
        select 1 from edac_criterion c
        where c.step = p_step and c.is_active and c.is_required
          and not exists (
              select 1 from edac_answer a
              where a.training_id = p_training and a.criterion_id = c.id
                and a.customer_id is not distinct from p_customer
          )
    );
$$;

-- -----------------------------------------------------------------------------
-- Today's coaching plans (legacy GetListEDACTrainingSale)
-- -----------------------------------------------------------------------------

create or replace function mgr_edac_sessions(p_date date)
returns jsonb
language sql
stable
security definer
set search_path = public, pg_temp
as $$
    select coalesce(jsonb_agg(r order by r ->> 'from_at'), '[]'::jsonb)
    from (
        select jsonb_build_object(
            'plan_id', wp.id,
            'content', wp.content,
            'from_at', wp.from_at,
            'to_at', wp.to_at,
            'plan_status', wp.status,
            'rep', jsonb_build_object('id', s.id, 'code', s.code, 'name', s.full_name),
            'training', (
                select jsonb_build_object('id', t.id, 'current_step', t.current_step, 'status', t.status,
                                          'check_in_at', t.check_in_at, 'check_out_at', t.check_out_at)
                from edac_training t where t.plan_id = wp.id
            )
        ) as r
        from work_plan wp
        join work_with_category c on c.id = wp.category_id and c.field_training
        join work_plan_object o on o.plan_id = wp.id and o.kind = 'salesperson'
        join salesperson s on s.id = o.target_id
        where wp.salesperson_id = current_salesperson_id()
          and wp.status in ('approved', 'completed')
          and p_date between (wp.from_at at time zone 'Asia/Ho_Chi_Minh')::date
                         and (wp.to_at   at time zone 'Asia/Ho_Chi_Minh')::date
    ) x;
$$;

-- -----------------------------------------------------------------------------
-- Start a coaching day (legacy StartEDACTraining)
--
-- From a plan already on the supervisor's day, or straight from a rep — in
-- which case the plan is written on the spot, since every training hangs off
-- one. An unfinished training of the same rep today is reopened rather than
-- doubled.
-- -----------------------------------------------------------------------------

create or replace function mgr_edac_start(p_rep uuid default null, p_plan_id uuid default null)
returns uuid
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
    v_me       uuid := current_salesperson_id();
    v_today    date := (now() at time zone 'Asia/Ho_Chi_Minh')::date;
    v_rep      uuid := p_rep;
    v_plan     uuid := p_plan_id;
    v_category uuid;
    v_id       uuid;
    v_name     text;
    v_branch   uuid;
    v_from     timestamptz;
    v_to       timestamptz;
begin
    if v_plan is not null then
        select o.target_id into v_rep
        from work_plan wp
        join work_with_category c on c.id = wp.category_id and c.field_training
        join work_plan_object o on o.plan_id = wp.id and o.kind = 'salesperson'
        where wp.id = v_plan and wp.salesperson_id = v_me and wp.status = 'approved';
        if not found then
            raise exception 'kế hoạch không phải buổi huấn luyện đang mở của bạn';
        end if;
        select id into v_id from edac_training where plan_id = v_plan;
        if v_id is not null then
            return v_id;
        end if;
    else
        if v_rep is null or not is_team_member(v_rep) then
            raise exception 'chọn nhân viên trong nhóm của bạn';
        end if;
        select id into v_id from edac_training
        where supervisor_id = v_me and salesperson_id = v_rep
          and training_date = v_today and status = 'in_progress';
        if v_id is not null then
            return v_id;
        end if;

        select id into v_category from work_with_category
        where field_training and is_active and object_kind = 'salesperson'
        order by sort_order limit 1;
        if v_category is null then
            raise exception 'chưa cấu hình loại công việc huấn luyện';
        end if;

        select full_name into v_name from salesperson where id = v_rep;
        select branch_id into v_branch from salesperson where id = v_me;
        v_from := least(now(), (v_today + time '08:00') at time zone 'Asia/Ho_Chi_Minh');
        v_to   := greatest(now() + interval '1 hour', (v_today + time '17:00') at time zone 'Asia/Ho_Chi_Minh');

        insert into work_plan (salesperson_id, author_id, branch_id, category_id, content,
                               from_at, to_at, status, approved_at)
        values (v_me, v_me, v_branch, v_category, 'Huấn luyện EDAC – ' || v_name,
                v_from, v_to, 'approved', now())
        returning id into v_plan;

        insert into work_plan_object (plan_id, kind, target_id) values (v_plan, 'salesperson', v_rep);
        insert into work_plan_history (plan_id, status, actor_id, reason)
        values (v_plan, 'approved', v_me, 'Giám sát bắt đầu huấn luyện EDAC');
    end if;

    insert into edac_training (plan_id, supervisor_id, salesperson_id, branch_id, training_date)
    select v_plan, v_me, v_rep, s.branch_id, v_today from salesperson s where s.id = v_rep
    returning id into v_id;
    return v_id;
end;
$$;

-- -----------------------------------------------------------------------------
-- One coaching day: header, the rep's KPIs, which steps are done
-- -----------------------------------------------------------------------------

create or replace function mgr_edac_training(p_id uuid)
returns jsonb
language plpgsql
stable
security definer
set search_path = public, pg_temp
as $$
declare
    t   edac_training := edac_training_of(p_id);
    v_kpi jsonb := mgr_dashboard(t.training_date, t.salesperson_id);
begin
    return jsonb_build_object(
        'id', t.id,
        'plan_id', t.plan_id,
        'training_date', t.training_date,
        'current_step', t.current_step,
        'status', t.status,
        'check_in_at', t.check_in_at,
        'check_out_at', t.check_out_at,
        'result', t.result,
        'supervisor_photo', t.supervisor_photo,
        'salesperson_photo', t.salesperson_photo,
        'rep', (select jsonb_build_object('id', s.id, 'code', s.code, 'name', s.full_name)
                from salesperson s where s.id = t.salesperson_id),
        'today', v_kpi -> 'today',
        'month', v_kpi -> 'month',
        -- Outlets with something recorded, per step: the progress the step cards show.
        'outlets_done', jsonb_build_object(
            '1', (select count(distinct customer_id) from edac_answer where training_id = t.id and step = 1),
            '3', (select count(*) from edac_customer_note where training_id = t.id and step = 3),
            '4', (select count(distinct customer_id) from edac_answer where training_id = t.id and step = 4),
            '5', (select count(distinct customer_id) from edac_answer where training_id = t.id and step = 5))
    );
end;
$$;

-- -----------------------------------------------------------------------------
-- A step's form (legacy Get*StepDetail and GetEDACCustomerListManager)
--
-- For a per-outlet step without an outlet: the rep's outlets for the day —
-- those due on the training date plus any already scored — each with the
-- steps recorded there. Otherwise: the criteria, their options and what was
-- answered; step 2 adds a tally of step 1, step 3 the note.
-- -----------------------------------------------------------------------------

create or replace function mgr_edac_step(p_id uuid, p_step smallint, p_customer uuid default null)
returns jsonb
language plpgsql
stable
security definer
set search_path = public, pg_temp
as $$
declare
    t edac_training := edac_training_of(p_id);
begin
    if p_step not between 0 and 6 then
        raise exception 'bước không hợp lệ';
    end if;

    if edac_is_outlet_step(p_step) and p_customer is null then
        return jsonb_build_object('step', p_step, 'outlets', coalesce((
            select jsonb_agg(jsonb_build_object(
                'customer_id', c.id, 'code', c.code, 'name', c.name, 'address', c.address,
                'due', x.due,
                'done_steps', (
                    select coalesce(jsonb_agg(distinct a.step order by a.step), '[]'::jsonb)
                    from (select step from edac_answer where training_id = t.id and customer_id = c.id
                          union select step from edac_customer_note where training_id = t.id and customer_id = c.id) a)
            ) order by not x.due, x.visit_order, c.code)
            from (
                select rc.customer_id,
                       bool_or(on_route_day(rc.visit_weekdays, rc.visit_weeks, t.training_date)) as due,
                       min(rc.visit_order) as visit_order
                from route_customer rc
                join sales_route r on r.id = rc.sales_route_id and r.is_active
                where rc.is_active and r.salesperson_id = t.salesperson_id
                group by rc.customer_id
            ) x
            join customer c on c.id = x.customer_id
            where x.due
               or exists (select 1 from edac_answer a where a.training_id = t.id and a.customer_id = c.id)
               or exists (select 1 from edac_customer_note n where n.training_id = t.id and n.customer_id = c.id)
        ), '[]'::jsonb));
    end if;

    if p_customer is not null and not edac_rep_outlet(t.salesperson_id, p_customer) then
        raise exception 'điểm bán không thuộc tuyến của nhân viên';
    end if;

    return jsonb_build_object(
        'step', p_step,
        'customer', (select jsonb_build_object('id', c.id, 'code', c.code, 'name', c.name, 'address', c.address)
                     from customer c where c.id = p_customer),
        'note', (select note from edac_customer_note
                 where training_id = t.id and customer_id = p_customer and step = p_step),
        'criteria', coalesce((
            select jsonb_agg(jsonb_build_object(
                'id', c.id, 'content', c.content, 'kind', c.kind, 'kpi', c.kpi, 'required', c.is_required,
                'options', (select coalesce(jsonb_agg(jsonb_build_object('value', o.value, 'label', o.label)
                                                      order by o.value), '[]'::jsonb)
                            from edac_criterion_option o where o.criterion_id = c.id),
                'answer', (select jsonb_build_object('value', a.option_value, 'yes', a.yes_no,
                                                     'text', a.text_value, 'note', a.note)
                           from edac_answer a
                           where a.training_id = t.id and a.criterion_id = c.id
                             and a.customer_id is not distinct from p_customer)
            ) order by c.sort_order)
            from edac_criterion c
            where c.step = p_step
              and (c.is_active or exists (select 1 from edac_answer a where a.training_id = t.id and a.criterion_id = c.id))
        ), '[]'::jsonb),
        -- Giải thích starts from what Quan sát found: per selling step, how many
        -- outlets saw it done and how many did not.
        'observed', case when p_step = 2 then coalesce((
            select jsonb_agg(jsonb_build_object('content', x.content, 'yes', x.yes, 'no', x.no) order by x.sort_order)
            from (
                select c.content, c.sort_order,
                       count(*) filter (where a.yes_no) as yes,
                       count(*) filter (where not a.yes_no) as no
                from edac_criterion c
                join edac_answer a on a.criterion_id = c.id and a.training_id = t.id
                where c.step = 1
                group by c.id, c.content, c.sort_order
            ) x
        ), '[]'::jsonb) end
    );
end;
$$;

-- -----------------------------------------------------------------------------
-- Save a step (legacy SaveStepEDACTraining)
--
-- p_answers: [{ criterion_id, value?, yes?, text?, note? }]. Whatever is sent is
-- upserted; once anything is, the step must be fully answered for that outlet
-- (or the plan). p_finish then moves current_step past it.
-- -----------------------------------------------------------------------------

create or replace function mgr_edac_save(
    p_id       uuid,
    p_step     smallint,
    p_customer uuid default null,
    p_answers  jsonb default '[]'::jsonb,
    p_note     text default null,
    p_finish   boolean default false
)
returns jsonb
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
    t        edac_training := edac_training_of(p_id);
    v_a      jsonb;
    v_crit   edac_criterion;
    v_value  smallint;
    v_yes    boolean;
    v_text   text;
    v_saved  boolean := false;
begin
    if t.status <> 'in_progress' then
        raise exception 'buổi huấn luyện đã kết thúc';
    end if;
    if p_step not between 0 and 6 then
        raise exception 'bước không hợp lệ';
    end if;
    -- Đánh giá runs alongside Áp dụng; every other step waits for the one before.
    if p_step > t.current_step and not (p_step = 5 and t.current_step >= 4) then
        raise exception 'hãy hoàn tất bước trước';
    end if;

    if edac_is_outlet_step(p_step) then
        if p_customer is null and (jsonb_array_length(coalesce(p_answers, '[]')) > 0 or p_note is not null) then
            raise exception 'chọn điểm bán';
        end if;
        if p_customer is not null and not edac_rep_outlet(t.salesperson_id, p_customer) then
            raise exception 'điểm bán không thuộc tuyến của nhân viên';
        end if;
    elsif p_customer is not null then
        raise exception 'bước này không theo điểm bán';
    end if;

    if p_step = 3 and p_customer is not null then
        if nullif(btrim(coalesce(p_note, '')), '') is null then
            raise exception 'nhập nội dung làm mẫu';
        end if;
        insert into edac_customer_note (training_id, customer_id, step, note)
        values (t.id, p_customer, 3, btrim(p_note))
        on conflict (training_id, customer_id, step) do update set note = excluded.note, noted_at = now();
    end if;

    for v_a in select * from jsonb_array_elements(coalesce(p_answers, '[]'::jsonb))
    loop
        select * into v_crit from edac_criterion where id = (v_a ->> 'criterion_id')::uuid and step = p_step;
        if not found then
            raise exception 'tiêu chí không thuộc bước này';
        end if;
        v_value := null; v_yes := null; v_text := null;
        case v_crit.kind
            when 'scale' then
                v_value := (v_a ->> 'value')::smallint;
                if not exists (select 1 from edac_criterion_option
                               where criterion_id = v_crit.id and value = v_value) then
                    raise exception 'chọn mức đánh giá cho "%"', v_crit.content;
                end if;
            when 'yes_no' then
                v_yes := (v_a ->> 'yes')::boolean;
                if v_yes is null then
                    raise exception 'chọn Có / Không cho "%"', v_crit.content;
                end if;
            when 'text' then
                v_text := nullif(btrim(coalesce(v_a ->> 'text', '')), '');
                if v_text is null then
                    raise exception 'nhập nội dung cho "%"', v_crit.content;
                end if;
        end case;

        insert into edac_answer (training_id, step, customer_id, criterion_id, option_value, yes_no, text_value, note)
        values (t.id, p_step, p_customer, v_crit.id, v_value, v_yes, v_text,
                nullif(btrim(coalesce(v_a ->> 'note', '')), ''))
        on conflict (training_id, criterion_id, coalesce(customer_id, '00000000-0000-0000-0000-000000000000'::uuid))
        do update set option_value = excluded.option_value, yes_no = excluded.yes_no,
                      text_value = excluded.text_value, note = excluded.note, answered_at = now();
        v_saved := true;
    end loop;

    if v_saved and not edac_step_answered(t.id, p_step, p_customer) then
        raise exception 'vui lòng trả lời đủ các tiêu chí';
    end if;

    if p_finish then
        if p_step > t.current_step then
            raise exception 'hãy hoàn tất bước trước';
        end if;
        if edac_is_outlet_step(p_step) then
            if not exists (select 1 from edac_answer where training_id = t.id and step = p_step)
               and not exists (select 1 from edac_customer_note where training_id = t.id and step = p_step) then
                raise exception 'cần ghi nhận ít nhất một điểm bán';
            end if;
        elsif not edac_step_answered(t.id, p_step, null) then
            raise exception 'vui lòng trả lời đủ các tiêu chí';
        end if;
        update edac_training set current_step = greatest(current_step, p_step + 1), updated_at = now()
        where id = t.id;
    else
        update edac_training set updated_at = now() where id = t.id;
    end if;

    return jsonb_build_object('current_step', (select current_step from edac_training where id = t.id));
end;
$$;

-- -----------------------------------------------------------------------------
-- Close the day (legacy SaveFinishStepEDACTraining)
-- -----------------------------------------------------------------------------

create or replace function mgr_edac_finish(
    p_id                uuid,
    p_result            text,
    p_supervisor_photo  text,
    p_salesperson_photo text
)
returns void
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
    t      edac_training := edac_training_of(p_id);
    v_me   uuid := current_salesperson_id();
    v_path text;
begin
    if t.status <> 'in_progress' then
        raise exception 'buổi huấn luyện đã kết thúc';
    end if;
    if t.current_step < 7 then
        raise exception 'hãy hoàn tất bước đánh giá cuối ngày';
    end if;
    if nullif(btrim(coalesce(p_result, '')), '') is null then
        raise exception 'nhập kết quả huấn luyện';
    end if;
    foreach v_path in array array[p_supervisor_photo, p_salesperson_photo] loop
        if v_path is null
           or v_path not like v_me::text || '/edac-' || t.id::text || '/%'
           or not exists (select 1 from storage.objects o where o.bucket_id = 'visit-photos' and o.name = v_path) then
            raise exception 'cần chụp ảnh giám sát và nhân viên';
        end if;
    end loop;

    update edac_training
    set status = 'completed', current_step = 7, check_out_at = now(), result = btrim(p_result),
        supervisor_photo = p_supervisor_photo, salesperson_photo = p_salesperson_photo, updated_at = now()
    where id = t.id;

    update work_plan
    set status = 'completed', result = btrim(p_result), completed_at = now(), updated_at = now()
    where id = t.plan_id and status = 'approved';
    if found then
        insert into work_plan_history (plan_id, status, actor_id, reason)
        values (t.plan_id, 'completed', v_me, 'Hoàn tất huấn luyện EDAC');
    end if;
end;
$$;

-- -----------------------------------------------------------------------------
-- History (legacy GetEDACTrainingResultDetail, edac_training_history)
-- -----------------------------------------------------------------------------

create or replace function mgr_edac_history(p_from date, p_to date, p_rep uuid default null)
returns jsonb
language sql
stable
security definer
set search_path = public, pg_temp
as $$
    select coalesce(jsonb_agg(jsonb_build_object(
        'id', t.id,
        'training_date', t.training_date,
        'current_step', t.current_step,
        'status', t.status,
        'check_in_at', t.check_in_at,
        'check_out_at', t.check_out_at,
        'result', t.result,
        'rep', jsonb_build_object('id', s.id, 'code', s.code, 'name', s.full_name),
        'outlets', (select count(distinct customer_id) from edac_answer a where a.training_id = t.id)
    ) order by t.training_date desc, t.check_in_at desc), '[]'::jsonb)
    from edac_training t
    join salesperson s on s.id = t.salesperson_id
    where t.supervisor_id = current_salesperson_id()
      and t.training_date between p_from and p_to
      and (p_rep is null or t.salesperson_id = p_rep);
$$;

/** Every answer of a day, grouped by step then outlet, for the result screen. */
create or replace function mgr_edac_result(p_id uuid)
returns jsonb
language plpgsql
stable
security definer
set search_path = public, pg_temp
as $$
declare
    t edac_training := edac_training_of(p_id);
begin
    return mgr_edac_training(p_id) || jsonb_build_object(
        'answers', coalesce((
            select jsonb_agg(jsonb_build_object(
                'step', a.step,
                'customer', case when a.customer_id is not null
                                 then (select c2.code || ' – ' || c2.name from customer c2 where c2.id = a.customer_id) end,
                'content', c.content,
                'answer', case c.kind
                              when 'scale'  then a.option_value || ' – ' ||
                                  (select label from edac_criterion_option o
                                   where o.criterion_id = c.id and o.value = a.option_value)
                              when 'yes_no' then case when a.yes_no then 'Có' else 'Không' end
                              else a.text_value end,
                'score', a.option_value,
                'yes', a.yes_no,
                'note', a.note
            ) order by a.step, a.customer_id nulls first, c.sort_order)
            from edac_answer a join edac_criterion c on c.id = a.criterion_id
            where a.training_id = t.id
        ), '[]'::jsonb),
        'notes', coalesce((
            select jsonb_agg(jsonb_build_object(
                'step', n.step, 'customer', c.code || ' – ' || c.name, 'note', n.note) order by c.code)
            from edac_customer_note n join customer c on c.id = n.customer_id
            where n.training_id = t.id
        ), '[]'::jsonb)
    );
end;
$$;

-- -----------------------------------------------------------------------------
-- Grants: the internal helpers to nobody, the mgr_ ones to signed-in users.
-- -----------------------------------------------------------------------------

revoke execute on function edac_training_of(uuid) from public, anon, authenticated;
revoke execute on function edac_rep_outlet(uuid, uuid) from public, anon, authenticated;
revoke execute on function edac_step_answered(uuid, smallint, uuid) from public, anon, authenticated;
revoke execute on function edac_is_outlet_step(smallint) from public, anon;

revoke execute on function mgr_edac_sessions(date) from public, anon;
revoke execute on function mgr_edac_start(uuid, uuid) from public, anon;
revoke execute on function mgr_edac_training(uuid) from public, anon;
revoke execute on function mgr_edac_step(uuid, smallint, uuid) from public, anon;
revoke execute on function mgr_edac_save(uuid, smallint, uuid, jsonb, text, boolean) from public, anon;
revoke execute on function mgr_edac_finish(uuid, text, text, text) from public, anon;
revoke execute on function mgr_edac_history(date, date, uuid) from public, anon;
revoke execute on function mgr_edac_result(uuid) from public, anon;

grant execute on function mgr_edac_sessions(date) to authenticated;
grant execute on function mgr_edac_start(uuid, uuid) to authenticated;
grant execute on function mgr_edac_training(uuid) to authenticated;
grant execute on function mgr_edac_step(uuid, smallint, uuid) to authenticated;
grant execute on function mgr_edac_save(uuid, smallint, uuid, jsonb, text, boolean) to authenticated;
grant execute on function mgr_edac_finish(uuid, text, text, text) to authenticated;
grant execute on function mgr_edac_history(date, date, uuid) to authenticated;
grant execute on function mgr_edac_result(uuid) to authenticated;
