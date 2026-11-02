-- "Viếng thăm 2/1" and "Độ phủ tuyến 200%": both dashboards divided every shop
-- reached by the shops on the route, so an off-route call (or a shop the rep
-- registered) pushed coverage past the plan. Visited now counts planned stops
-- reached, the stop's own day; off-route calls stay in the visit report, and
-- "who is out working" still counts any shop reached.
CREATE OR REPLACE FUNCTION public.dashboard_overview(p_date date)
 RETURNS jsonb
 LANGUAGE plpgsql
 STABLE
 SET search_path TO 'public', 'pg_temp'
AS $function$
declare
    v_sp              uuid    := current_salesperson_id();
    v_month_start     date    := date_trunc('month', p_date)::date;
    v_month_end       date    := (date_trunc('month', p_date) + interval '1 month - 1 day')::date;
    -- ISO weekday: Monday = 1, so subtracting isodow-1 always lands on Monday.
    v_week_start      date    := p_date - (extract(isodow from p_date)::integer - 1);
    v_weekday         smallint := extract(isodow from p_date)::smallint;

    v_today_revenue   bigint;
    v_today_orders    integer;
    v_today_sku       numeric;
    v_visit_done      integer;
    v_visit_planned   integer;
    v_month_revenue   bigint;
    v_month_orders    integer;
    v_revenue_target  bigint;
    v_order_target    integer;
begin
    if v_sp is null then
        raise exception 'no salesperson is linked to this account';
    end if;

    -- Cancelled orders are excluded everywhere below. They are kept as rows for
    -- audit, but counting them would tell the rep they sold something they did not.

    select coalesce(sum(total_amount), 0), count(*)
      into v_today_revenue, v_today_orders
      from sales_order
     where salesperson_id = v_sp
       and order_date = p_date
       and status <> 'cancelled';

    -- Distinct products per order, averaged. The legacy label is "SKU/đơn", and
    -- distinct matters: the same product in two units of measure is one SKU on
    -- the shelf, which is what the figure is about.
    select coalesce(avg(line_count), 0)
      into v_today_sku
      from (
            select count(distinct l.product_id) as line_count
              from sales_order o
              join sales_order_line l on l.order_id = o.id
             where o.salesperson_id = v_sp
               and o.order_date = p_date
               and o.status <> 'cancelled'
             group by o.id
           ) per_order;

    -- Reached, not merely planned: a stop the rep arrived at counts even when the
    -- outlet was shut or bought nothing. Those are visits that happened.
    --
    -- Distinct shops, not calls: see the note above this function. And only the
    -- day's route stops, because this is the numerator of "x of the planned y":
    -- an off-route call made it read 2/1.
    select count(distinct v.customer_id)
      into v_visit_done
      from visit v
     where v.salesperson_id = v_sp
       and v.visit_date = p_date
       and v.status in ('completed', 'no_order', 'closed')
       and exists (
           select 1
             from route_customer rc
             join sales_route r on r.id = rc.sales_route_id
            where r.salesperson_id = v_sp
              and rc.customer_id = v.customer_id
              and r.is_active
              and rc.is_active
              and on_route_day(rc.visit_weekdays, rc.visit_weeks, p_date)
       );

    select count(distinct rc.customer_id)
      into v_visit_planned
      from route_customer rc
      join sales_route r on r.id = rc.sales_route_id
     where r.salesperson_id = v_sp
       and r.is_active
       and rc.is_active
       and on_route_day(rc.visit_weekdays, rc.visit_weeks, p_date);

    select coalesce(sum(total_amount), 0), count(*)
      into v_month_revenue, v_month_orders
      from sales_order
     where salesperson_id = v_sp
       and order_date between v_month_start and v_month_end
       and status <> 'cancelled';

    -- Left null when head office has set nothing, and reported as null rather
    -- than zero: "no target" and "a target of zero" are different statements,
    -- and the screen says so differently.
    select revenue_target, order_target
      into v_revenue_target, v_order_target
      from sales_target
     where salesperson_id = v_sp
       and period_month = v_month_start;

    return jsonb_build_object(
        'date', p_date,
        'today', jsonb_build_object(
            'revenue',       v_today_revenue,
            'order_count',   v_today_orders,
            'visit_done',    v_visit_done,
            'visit_planned', v_visit_planned,
            'sku_per_order', round(v_today_sku, 1)
        ),
        'month', jsonb_build_object(
            'revenue',        v_month_revenue,
            'revenue_target', v_revenue_target,
            'order_count',    v_month_orders,
            'order_target',   v_order_target
        ),
        'charts', jsonb_build_object(
            'this_week',  daily_sales_series(v_sp, v_week_start, v_week_start + 6, false),
            'last_week',  daily_sales_series(v_sp, v_week_start - 7, v_week_start - 1, false),
            'this_month', daily_sales_series(v_sp, v_month_start, v_month_end, true)
        )
    );
end;
$function$
;

CREATE OR REPLACE FUNCTION public.mgr_dashboard(p_date date, p_salesperson_id uuid DEFAULT NULL::uuid, p_supervisor_id uuid DEFAULT NULL::uuid, p_branch_ids uuid[] DEFAULT NULL::uuid[])
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
    where s.id in (select team_reps(p_supervisor_id, p_branch_ids))
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
    -- Distinct: a shop on two of a rep's routes is still one stop that day.
    planned as (
        select distinct r.salesperson_id, rc.customer_id, d::date as day
        from generate_series(v_month_start, p_date, interval '1 day') d
        join sales_route r on r.salesperson_id = any (v_ids) and r.is_active
        join route_customer rc on rc.sales_route_id = r.id and rc.is_active
        where on_route_day(rc.visit_weekdays, rc.visit_weeks, d::date)
    ),
    -- The planned stops that were reached on their day: the numerator of every
    -- "x of y planned" below, so coverage cannot pass the plan. An off-route
    -- call is a visit, but not coverage of the route.
    reached as (
        select p.salesperson_id, p.customer_id, p.day
        from planned p
        where exists (
            select 1 from visits v
            where v.salesperson_id = p.salesperson_id
              and v.customer_id = p.customer_id
              and v.visit_date = p.day
        )
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
            (select count(*) from reached r
              where r.salesperson_id = s.id and r.day = p_date) as today_visits,
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
            'visit_done',    (select count(*) from reached where day = p_date),
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
            'visit_done',     (select count(*) from reached),
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
$function$;

