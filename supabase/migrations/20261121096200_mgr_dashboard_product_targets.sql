-- The manager overview's targets by product group and product: what head
-- office set for the month (sales_target_product, summed over the reps in
-- view) against what those reps sold of it, from order lines net of
-- cancellations and free goods.
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
    -- Targets by group or product, summed over the reps in view, with what the
    -- same reps sold of it this month (line amounts, free goods left out).
    product_targets as (
        select x.kind, x.ref_id, x.name, x.target,
               (select coalesce(sum(l.line_amount), 0)
                  from orders o
                  join sales_order_line l on l.order_id = o.id
                  join product p on p.id = l.product_id
                 where o.order_date between v_month_start and v_month_end
                   and o.salesperson_id in (
                       select t.salesperson_id from sales_target_product t
                        where t.period_month = v_month_start
                          and t.salesperson_id = any (v_ids)
                          and coalesce(t.category_id, t.product_id) = x.ref_id)
                   and not l.is_free
                   and (case when x.kind = 'category' then p.category_id else p.id end) = x.ref_id) as actual
        from (
            select case when t.category_id is not null then 'category' else 'product' end as kind,
                   coalesce(t.category_id, t.product_id) as ref_id,
                   coalesce(c.name, p.name) as name,
                   coalesce(c.sort_order, 1000) as sort_order,
                   sum(t.target_amount) as target
            from sales_target_product t
            left join product_category c on c.id = t.category_id
            left join product p on p.id = t.product_id
            where t.salesperson_id = any (v_ids) and t.period_month = v_month_start
            group by 1, 2, 3, 4
        ) x
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
        -- Groups first, then single products, each by name.
        'product_targets', coalesce((
            select jsonb_agg(jsonb_build_object(
                'kind', pt.kind, 'id', pt.ref_id, 'name', pt.name,
                'target', pt.target, 'actual', pt.actual
            ) order by pt.kind, pt.name)
            from product_targets pt
        ), '[]'::jsonb),
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
