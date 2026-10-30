-- =============================================================================
-- eSalesManager — Báo cáo "Kết quả huấn luyện"
--
-- Legacy API_GetTrainingResultBySupManager / …ListBySalseManager: for each rep,
-- how many coaching plans fell in the period, how many were trained through to
-- the end and how many were not. Here at SS level the plans are the caller's
-- own field-training plans (see 20261108090000_manager_edac.sql); a plan counts
-- as trained once its EDAC day is completed.
--
-- The score is what the legacy history screen showed per day, made comparable:
-- the step-5 (Đánh giá) answers, each as a share of its criterion's top option,
-- averaged. Scales run to 3, 4 or 5, so raw averages would not compare.
-- =============================================================================

create or replace function mgr_report_training(p_from date, p_to date, p_rep uuid default null)
returns jsonb
language sql
stable
security definer
set search_path = public, pg_temp
as $$
    with plans as (
        select wp.id as plan_id, wp.content, wp.from_at, wp.to_at,
               s.id as rep_id, s.code as rep_code, s.full_name as rep_name,
               t.id as training_id, t.status as training_status, t.current_step,
               t.training_date, t.result,
               case when t.status = 'completed' then 'completed'
                    when t.id is not null then 'in_progress'
                    else 'not_started' end as status,
               (select count(distinct a.customer_id) from edac_answer a where a.training_id = t.id) as outlets,
               (select round(avg(a.option_value::numeric * 100
                                 / (select max(o.value) from edac_criterion_option o where o.criterion_id = a.criterion_id)))
                from edac_answer a where a.training_id = t.id and a.step = 5 and a.option_value is not null) as score
        from work_plan wp
        join work_with_category c on c.id = wp.category_id and c.field_training
        join work_plan_object o on o.plan_id = wp.id and o.kind = 'salesperson'
        join salesperson s on s.id = o.target_id
        left join edac_training t on t.plan_id = wp.id
        where wp.salesperson_id = current_salesperson_id()
          and wp.status in ('approved', 'completed')
          and (wp.from_at at time zone 'Asia/Ho_Chi_Minh')::date <= p_to
          and (wp.to_at   at time zone 'Asia/Ho_Chi_Minh')::date >= p_from
          and (p_rep is null or s.id = p_rep)
    )
    select jsonb_build_object(
        'summary', jsonb_build_object(
            'plans',     (select count(*) from plans),
            'trained',   (select count(*) from plans where status = 'completed'),
            'untrained', (select count(*) from plans where status <> 'completed'),
            'outlets',   (select coalesce(sum(outlets), 0) from plans),
            'score',     (select round(avg(score)) from plans where score is not null)),
        'members', coalesce((
            select jsonb_agg(jsonb_build_object(
                'id', rep_id, 'code', rep_code, 'name', rep_name,
                'plans', n, 'trained', trained, 'untrained', n - trained, 'score', score
            ) order by rep_code)
            from (
                select rep_id, rep_code, rep_name, count(*) as n,
                       count(*) filter (where status = 'completed') as trained,
                       round(avg(score)) as score
                from plans group by rep_id, rep_code, rep_name
            ) m
        ), '[]'::jsonb),
        'items', coalesce((
            select jsonb_agg(jsonb_build_object(
                'plan_id', plan_id, 'training_id', training_id, 'content', content,
                'from_at', from_at, 'to_at', to_at,
                'rep', jsonb_build_object('id', rep_id, 'code', rep_code, 'name', rep_name),
                'status', status, 'current_step', current_step, 'result', result,
                'outlets', outlets, 'score', score
            ) order by from_at desc)
            from plans
        ), '[]'::jsonb)
    );
$$;

revoke execute on function mgr_report_training(date, date, uuid) from public, anon;
grant execute on function mgr_report_training(date, date, uuid) to authenticated;
