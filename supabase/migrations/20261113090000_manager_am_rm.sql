-- =============================================================================
-- eSalesManager — AM / RM (quản lý khu vực / vùng)
--
-- team_salesperson_ids() has been recursive from the start, so an AM already
-- reaches every SS below them and every rep below those, and an RM the AMs'
-- subtrees: approvals, plans, routes and notifications needed no change. What
-- did: the team now holds managers as well as reps, and sales figures must be
-- the reps' alone. team_reps() is that set, optionally narrowed to the subtree
-- of one supervisor the caller manages (the legacy Filter's SS level).
--
-- An SS's own leave request now reaches their AM: the AM's team contains the SS
-- and mgr_leave_list / mgr_decide_leave already go by team membership — the
-- question left open since G10 ("ai duyệt nghỉ của SS?").
-- =============================================================================

/**
 * Active reps (position sr) below the caller; with p_supervisor, only those
 * below that supervisor, who must be the caller or in the caller's team.
 */
create or replace function team_reps(p_supervisor uuid default null)
returns setof uuid
language plpgsql
stable
security definer
set search_path = public, pg_temp
as $$
begin
    if p_supervisor is not null
       and p_supervisor <> current_salesperson_id()
       and not is_team_member(p_supervisor) then
        raise exception 'giám sát không thuộc nhóm của bạn';
    end if;
    return query
        with recursive tree (id) as (
            select s.id from salesperson s
            where s.supervisor_id = coalesce(p_supervisor, current_salesperson_id())
            union
            select s.id from salesperson s join tree t on s.supervisor_id = t.id
        )
        select s.id from salesperson s
        where s.id in (select id from tree) and s.position = 'sr' and s.is_active;
end;
$$;

revoke execute on function team_reps(uuid) from public, anon;
grant execute on function team_reps(uuid) to authenticated;

drop function mgr_dashboard(date, uuid);

CREATE OR REPLACE FUNCTION public.mgr_dashboard(p_date date, p_salesperson_id uuid DEFAULT NULL::uuid, p_supervisor_id uuid DEFAULT NULL::uuid)
 RETURNS jsonb
 LANGUAGE plpgsql
 STABLE SECURITY DEFINER
 SET search_path TO 'public', 'pg_temp'
AS $function$
declare
    v_ids         uuid[];
    v_month_start date := date_trunc('month', p_date)::date;
    v_month_end   date := (date_trunc('month', p_date) + interval '1 month - 1 day')::date;
    v_week_start  date := p_date - (extract(isodow from p_date)::integer - 1);
    v_result      jsonb;
begin
    select array_agg(s.id order by s.code) into v_ids
    from salesperson s
    where s.id in (select team_reps(p_supervisor_id))
      and s.is_active
      and (p_salesperson_id is null or s.id = p_salesperson_id);

    if p_salesperson_id is not null and v_ids is null then
        raise exception 'nhân viên không thuộc nhóm của bạn';
    end if;
    v_ids := coalesce(v_ids, '{}');

    with
    orders as (
        select o.id, o.salesperson_id, o.customer_id, o.order_date, o.total_amount
        from sales_order o
        where o.salesperson_id = any (v_ids)
          and o.order_date between least(v_month_start, v_week_start - 7) and v_month_end
          and o.status <> 'cancelled'
    ),
    order_skus as (
        select o.id, o.salesperson_id, o.order_date, count(distinct l.product_id) as skus
        from orders o
        join sales_order_line l on l.order_id = o.id
        group by o.id, o.salesperson_id, o.order_date
    ),
    visits as (
        select distinct v.salesperson_id, v.customer_id, v.visit_date
        from visit v
        where v.salesperson_id = any (v_ids)
          and v.visit_date between v_month_start and v_month_end
          and v.status in ('completed', 'no_order', 'closed')
    ),
    -- One row per planned stop per day, from the month's start to the selected day.
    planned as (
        select r.salesperson_id, rc.customer_id, d::date as day
        from generate_series(v_month_start, p_date, interval '1 day') d
        join sales_route r on r.salesperson_id = any (v_ids) and r.is_active
        join route_customer rc on rc.sales_route_id = r.id and rc.is_active
        where on_route_day(rc.visit_weekdays, rc.visit_weeks, d::date)
    ),
    targets as (
        select t.salesperson_id, t.revenue_target, t.order_target
        from sales_target t
        where t.salesperson_id = any (v_ids) and t.period_month = v_month_start
    ),
    member_rows as (
        select
            s.id, s.code, s.full_name,
            (select coalesce(sum(total_amount), 0) from orders o
              where o.salesperson_id = s.id and o.order_date = p_date) as today_revenue,
            (select count(*) from orders o
              where o.salesperson_id = s.id and o.order_date = p_date) as today_orders,
            (select count(*) from visits v
              where v.salesperson_id = s.id and v.visit_date = p_date) as today_visits,
            (select count(*) from planned p
              where p.salesperson_id = s.id and p.day = p_date) as today_planned,
            (select coalesce(sum(total_amount), 0) from orders o
              where o.salesperson_id = s.id and o.order_date between v_month_start and v_month_end) as month_revenue,
            (select count(*) from orders o
              where o.salesperson_id = s.id and o.order_date between v_month_start and v_month_end) as month_orders,
            (select t.revenue_target from targets t where t.salesperson_id = s.id) as revenue_target
        from salesperson s
        where s.id = any (v_ids)
    )
    select jsonb_build_object(
        'date', p_date,
        'team_size', cardinality(v_ids),
        'today', jsonb_build_object(
            'revenue',       (select coalesce(sum(total_amount), 0) from orders where order_date = p_date),
            'order_count',   (select count(*) from orders where order_date = p_date),
            'visit_done',    (select count(*) from visits where visit_date = p_date),
            'visit_planned', (select count(*) from planned where day = p_date),
            'sku_per_order', (select round(coalesce(avg(skus), 0), 1) from order_skus where order_date = p_date),
            -- Reps who have reached at least one shop today: who is out working.
            'active_reps',   (select count(distinct salesperson_id) from visits where visit_date = p_date)
        ),
        'month', jsonb_build_object(
            'revenue',        (select coalesce(sum(total_amount), 0) from orders
                                where order_date between v_month_start and v_month_end),
            'revenue_target', (select sum(revenue_target) from targets),
            'order_count',    (select count(*) from orders
                                where order_date between v_month_start and v_month_end),
            'order_target',   (select sum(order_target) from targets),
            'visit_done',     (select count(*) from visits where visit_date <= p_date),
            'visit_planned',  (select count(*) from planned),
            'sku_per_order',  (select round(coalesce(avg(skus), 0), 1) from order_skus
                                where order_date between v_month_start and v_month_end),
            -- VPO: value per outlet that ordered, the legacy's Output / CustOrdered.
            'vpo', (select coalesce(round(sum(total_amount)::numeric / nullif(count(distinct customer_id), 0)), 0)
                    from orders where order_date between v_month_start and v_month_end),
            'new_customers', (select count(*) from customer c
                    where c.created_by_salesperson_id = any (v_ids)
                      and (c.created_at at time zone 'Asia/Ho_Chi_Minh')::date between v_month_start and v_month_end)
        ),
        -- Ranked by the month so far: the list a supervisor reads to know who
        -- to call first.
        'members', coalesce((
            select jsonb_agg(jsonb_build_object(
                'id', m.id, 'code', m.code, 'name', m.full_name,
                'today_revenue', m.today_revenue,
                'today_orders',  m.today_orders,
                'today_visits',  m.today_visits,
                'today_planned', m.today_planned,
                'month_revenue', m.month_revenue,
                'month_orders',  m.month_orders,
                'revenue_target', m.revenue_target
            ) order by m.month_revenue desc, m.code)
            from member_rows m
        ), '[]'::jsonb),
        'charts', jsonb_build_object(
            'this_week',  (select jsonb_agg(jsonb_build_object('title', x.title, 'actual', x.actual) order by x.day) from (
                select d::date as day,
                       case when extract(isodow from d) = 7 then 'CN' else 'T' || (extract(isodow from d)::integer + 1) end as title,
                       (select coalesce(sum(total_amount), 0) from orders o where o.order_date = d::date) as actual
                from generate_series(v_week_start, v_week_start + 6, interval '1 day') d) x),
            'last_week',  (select jsonb_agg(jsonb_build_object('title', x.title, 'actual', x.actual) order by x.day) from (
                select d::date as day,
                       case when extract(isodow from d) = 7 then 'CN' else 'T' || (extract(isodow from d)::integer + 1) end as title,
                       (select coalesce(sum(total_amount), 0) from orders o where o.order_date = d::date) as actual
                from generate_series(v_week_start - 7, v_week_start - 1, interval '1 day') d) x),
            'this_month', (select jsonb_agg(jsonb_build_object('title', x.title, 'actual', x.actual) order by x.day) from (
                select d::date as day, to_char(d, 'FMDD') as title,
                       (select coalesce(sum(total_amount), 0) from orders o where o.order_date = d::date) as actual
                from generate_series(v_month_start, v_month_end, interval '1 day') d) x)
        )
    ) into v_result;

    return v_result;
end;
$function$
;

CREATE OR REPLACE FUNCTION public.mgr_sales_focus(p_date date)
 RETURNS jsonb
 LANGUAGE sql
 STABLE SECURITY DEFINER
 SET search_path TO 'public', 'pg_temp'
AS $function$
    with team as (
        select s.id, s.code, s.full_name, s.branch_id
        from salesperson s where s.id in (select team_reps())
    ),
    branches as (select distinct branch_id from team),
    focus as (
        select f.id, f.product_id, f.from_date, f.to_date, f.priority, f.target_base_qty, f.note,
               p.code, p.name, p.base_uom
        from focus_product f
        join product p on p.id = f.product_id and p.is_active
        where f.is_active
          and p_date between f.from_date and f.to_date
          and (f.branch_id is null or f.branch_id in (select branch_id from branches))
    ),
    sold as (
        -- Every rep gets a line, the ones who have not sold it yet included:
        -- they are the line the supervisor is looking for.
        select f.id as focus_id, t.id as rep_id, t.code, t.full_name,
               coalesce(x.qty, 0) as qty, coalesce(x.outlets, 0) as outlets
        from focus f
        cross join team t
        left join lateral (
            select sum(l.base_qty) as qty, count(distinct o.customer_id) as outlets
            from sales_order o
            join sales_order_line l on l.order_id = o.id and l.product_id = f.product_id
            where o.salesperson_id = t.id and o.status <> 'cancelled'
              and o.order_date between f.from_date and f.to_date
        ) x on true
    )
    select jsonb_build_object(
        'date', p_date,
        'products', coalesce((
            select jsonb_agg(jsonb_build_object(
                'focus_id', f.id, 'product_code', f.code, 'product_name', f.name, 'base_uom', f.base_uom,
                'from_date', f.from_date, 'to_date', f.to_date, 'priority', f.priority,
                -- The target is per rep in focus_product; the team's is the sum.
                'target_base_qty', f.target_base_qty,
                'team_target', f.target_base_qty * (select count(*) from team),
                'note', f.note,
                'sold_base_qty', (select coalesce(sum(s.qty), 0) from sold s where s.focus_id = f.id),
                -- Distinct across the team: two reps share outlets, so summing theirs
                -- would count a shop twice.
                'outlets', (select count(distinct o.customer_id)
                            from sales_order o
                            join sales_order_line l on l.order_id = o.id and l.product_id = f.product_id
                            where o.salesperson_id in (select id from team) and o.status <> 'cancelled'
                              and o.order_date between f.from_date and f.to_date),
                'reps', (select coalesce(jsonb_agg(jsonb_build_object(
                             'code', s.code, 'name', s.full_name, 'sold_base_qty', s.qty, 'outlets', s.outlets)
                             order by s.code), '[]'::jsonb)
                         from sold s where s.focus_id = f.id)
            ) order by f.priority, f.name)
            from focus f
        ), '[]'::jsonb),
        'programs', coalesce((
            select jsonb_agg(x order by x ->> 'kind', x ->> 'code')
            from (
                select jsonb_build_object(
                    'kind', 'display', 'code', d.code, 'name', d.name,
                    'from_date', d.from_date, 'to_date', d.to_date,
                    'registered', (select count(distinct r.customer_id) from display_registration r
                                   where r.program_id = d.id and r.status in ('pending', 'approved')
                                     and trade_registration_rep(r.salesperson_id, r.customer_id) in (select id from team))
                ) as x
                from display_program d
                where d.is_active and p_date between d.from_date and d.to_date
                  and (d.branch_id is null or d.branch_id in (select branch_id from branches))
                union all
                select jsonb_build_object(
                    'kind', 'loyalty', 'code', l.code, 'name', l.name,
                    'from_date', l.from_date, 'to_date', l.to_date,
                    'registered', (select count(distinct r.customer_id) from loyalty_registration r
                                   where r.program_id = l.id and r.status in ('pending', 'approved')
                                     and trade_registration_rep(r.salesperson_id, r.customer_id) in (select id from team))
                )
                from loyalty_program l
                where l.is_active and p_date between l.from_date and l.to_date
                  and (l.branch_id is null or l.branch_id in (select branch_id from branches))
            ) p
        ), '[]'::jsonb)
    );
$function$
;

revoke execute on function mgr_dashboard(date, uuid, uuid) from public, anon;
grant execute on function mgr_dashboard(date, uuid, uuid) to authenticated;
