-- =============================================================================
-- Honour route_customer.visit_weeks — the 4-week MCL cycle.
--
-- visit_weeks was stored from the start (legacy PPC_WeekofVisitMCL: F1 W159 =
-- weeks 1,5,9…, F2 OW/EW = odd/even weeks, NA = every week) but nothing read
-- it: today's route, the dashboards and the activity report all looked at the
-- weekday alone. Since the manager app now lets a supervisor put an outlet on
-- "week 2 only", the SFA side has to agree on what week it is.
--
-- The cycle runs continuously in whole ISO weeks from a fixed Monday, so it
-- never resets at a month or year boundary (a month has 4–6 partial weeks and
-- a year 52 or 53, either of which would break an every-other-week rhythm).
-- functions/route/index.ts repeats the arithmetic; keep the two in step.
-- =============================================================================

/** Week 1..4 of the MCL cycle the date falls in. 2024-01-01 (a Monday) is week 1. */
create or replace function mcl_week(p_date date)
returns smallint
language sql
immutable
set search_path = public
as $$
    select (((p_date - (extract(isodow from p_date)::integer - 1) - date '2024-01-01') / 7) % 4 + 4) % 4 + 1;
$$;

/** Whether an outlet with this schedule is due on the date. Empty weeks = every week. */
create or replace function on_route_day(p_weekdays smallint[], p_weeks smallint[], p_date date)
returns boolean
language sql
immutable
set search_path = public
as $$
    select extract(isodow from p_date)::smallint = any (p_weekdays)
       and (cardinality(p_weeks) = 0 or mcl_week(p_date) = any (p_weeks));
$$;

grant execute on function mcl_week(date) to authenticated;
grant execute on function on_route_day(smallint[], smallint[], date) to authenticated;
revoke execute on function mcl_week(date) from public, anon;
revoke execute on function on_route_day(smallint[], smallint[], date) from public, anon;

-- The functions below are unchanged apart from the weekday test, which now
-- goes through on_route_day().

CREATE OR REPLACE FUNCTION public.daily_sales_targets(p_date date)
 RETURNS jsonb
 LANGUAGE plpgsql
 STABLE
 SET search_path TO 'public', 'pg_temp'
AS $function$
declare
    v_sp      uuid     := current_salesperson_id();
    v_weekday smallint := extract(isodow from p_date)::smallint;
begin
    if v_sp is null then
        raise exception 'no salesperson is linked to this account';
    end if;

    return coalesce((
        select jsonb_agg(row order by (row ->> 'visit_order')::integer)
          from (
                select jsonb_build_object(
                           'customer_id',   c.id,
                           'customer_code', c.code,
                           'customer_name', c.name,
                           'address',       c.address,
                           'visit_order',   rc.visit_order,
                           'target',        coalesce(t.target_amount, 0),
                           'has_target',    t.id is not null,
                           'last_amount',   o.total_amount,
                           'last_date',     o.order_date
                       ) as row
                  from route_customer rc
                  join sales_route r on r.id = rc.sales_route_id
                  join customer c on c.id = rc.customer_id
                  left join daily_sales_target t
                         on t.customer_id = c.id
                        and t.salesperson_id = v_sp
                        and t.target_date = p_date
                  -- The most recent order this rep took at this outlet, if any.
                  left join lateral (
                        select so.total_amount, so.order_date
                          from sales_order so
                         where so.customer_id = c.id
                           and so.salesperson_id = v_sp
                           and so.status <> 'cancelled'
                         order by so.order_date desc
                         limit 1
                       ) o on true
                 where r.salesperson_id = v_sp
                   and r.is_active
                   and rc.is_active
                   and on_route_day(rc.visit_weekdays, rc.visit_weeks, p_date)
               ) per_stop
    ), '[]'::jsonb);
end;
$function$
;

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
    -- Distinct shops, not calls: see the note above this function.
    select count(distinct customer_id)
      into v_visit_done
      from visit
     where salesperson_id = v_sp
       and visit_date = p_date
       and status in ('completed', 'no_order', 'closed');

    select count(*)
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

CREATE OR REPLACE FUNCTION public.mgr_dashboard(p_date date, p_salesperson_id uuid DEFAULT NULL::uuid)
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
    where s.id in (select team_salesperson_ids())
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

CREATE OR REPLACE FUNCTION public.mgr_work_with_route(p_rep uuid, p_date date)
 RETURNS jsonb
 LANGUAGE plpgsql
 STABLE SECURITY DEFINER
 SET search_path TO 'public', 'pg_temp'
AS $function$
declare
    v_me      uuid := current_salesperson_id();
    v_weekday smallint := extract(isodow from p_date)::smallint;
begin
    if not is_team_member(p_rep) then
        raise exception 'nhân viên không thuộc nhóm của bạn';
    end if;

    return (
        with planned as (
            select distinct on (rc.customer_id) rc.customer_id, rc.visit_order
            from route_customer rc
            join sales_route r on r.id = rc.sales_route_id
            where r.salesperson_id = p_rep and r.is_active and rc.is_active
              and on_route_day(rc.visit_weekdays, rc.visit_weeks, p_date)
            order by rc.customer_id, rc.visit_order
        ),
        -- A shop the rep called on off-route today is still on today's ride.
        stops as (
            select customer_id, visit_order, true as planned from planned
            union
            select v.customer_id, 999, false
            from visit v
            where v.salesperson_id = p_rep and v.visit_date = p_date
              and v.customer_id not in (select customer_id from planned)
        )
        select jsonb_build_object(
            'date', p_date,
            'rep', staff_brief(p_rep),
            -- The supervisor's open call, wherever it is: one at a time.
            'open_visit_id', (select v.id from visit v
                               where v.salesperson_id = v_me and v.visit_date = p_date
                                 and v.status = 'in_progress' limit 1),
            'stops', coalesce(jsonb_agg(jsonb_build_object(
                'customer_id',  c.id,
                'code',         c.code,
                'name',         c.name,
                'address',      c.address,
                'lat',          c.lat,
                'lng',          c.lng,
                'planned',      s.planned,
                'rep_status',   (select v.status from visit v
                                  where v.salesperson_id = p_rep and v.customer_id = c.id
                                    and v.visit_date = p_date
                                  order by v.check_in_at desc nulls last limit 1),
                'rep_check_in', (select min(v.check_in_at) from visit v
                                  where v.salesperson_id = p_rep and v.customer_id = c.id
                                    and v.visit_date = p_date),
                'rep_order_amount', (select coalesce(sum(o.total_amount), 0) from sales_order o
                                  where o.salesperson_id = p_rep and o.customer_id = c.id
                                    and o.order_date = p_date and o.status <> 'cancelled'),
                'my_visit_id',  (select v.id from visit v
                                  where v.salesperson_id = v_me and v.customer_id = c.id
                                    and v.visit_date = p_date
                                  order by v.check_in_at desc nulls last limit 1),
                'my_status',    (select v.status from visit v
                                  where v.salesperson_id = v_me and v.customer_id = c.id
                                    and v.visit_date = p_date
                                  order by v.check_in_at desc nulls last limit 1)
            ) order by s.visit_order, c.code), '[]'::jsonb)
        )
        from stops s
        join customer c on c.id = s.customer_id
    );
end;
$function$
;

CREATE OR REPLACE FUNCTION public.report_activities_of(p_sps uuid[], p_date date)
 RETURNS jsonb
 LANGUAGE plpgsql
 STABLE SECURITY DEFINER
 SET search_path TO 'public', 'pg_temp'
AS $function$
declare
    v_sps       uuid[]   := readable_salespeople(p_sps);
    v_weekday   smallint := extract(isodow from p_date)::smallint;
    v_planned   integer;
    v_summary   jsonb;
    v_rows      jsonb;
    v_members   jsonb;
begin
    select count(*)
      into v_planned
      from route_customer rc
      join sales_route r on r.id = rc.sales_route_id
     where r.salesperson_id = any (v_sps)
       and r.is_active
       and rc.is_active
       and on_route_day(rc.visit_weekdays, rc.visit_weeks, p_date);

    with visits as (
        select v.id,
               v.salesperson_id,
               v.customer_id,
               v.status,
               v.check_in_at,
               v.check_out_at,
               c.code    as customer_code,
               c.name    as customer_name,
               c.address as customer_address,
               -- On the visiting rep's MCP for this weekday. An outlet the rep
               -- registered themselves is not, and neither is one visited off-route.
               exists (
                   select 1
                     from route_customer rc
                     join sales_route r on r.id = rc.sales_route_id
                    where r.salesperson_id = v.salesperson_id
                      and rc.customer_id = v.customer_id
                      and r.is_active
                      and rc.is_active
                      and on_route_day(rc.visit_weekdays, rc.visit_weeks, p_date)
               ) as planned,
               coalesce((
                   select sum(o.total_amount)
                     from sales_order o
                    where o.visit_id = v.id
                      and o.status <> 'cancelled'
               ), 0) as order_amount
          from visit v
          join customer c on c.id = v.customer_id
         where v.salesperson_id = any (v_sps)
           and v.visit_date = p_date
    )
    select jsonb_build_object(
               'planned',      v_planned,
               -- Every count below is of shops, not of calls (per rep: the same
               -- shop visited by two reps is two shops reached).
               'visited',      count(distinct (salesperson_id, customer_id)) filter (where check_in_at is not null),
               'unplanned',    count(distinct (salesperson_id, customer_id)) filter (where not planned),
               'strike',       count(distinct (salesperson_id, customer_id)) filter (where order_amount > 0),
               'non_strike',   count(distinct (salesperson_id, customer_id)) filter (where check_in_at is not null)
                               - count(distinct (salesperson_id, customer_id)) filter (where order_amount > 0),
               'closed',       count(distinct (salesperson_id, customer_id)) filter (where status = 'closed'),
               'order_amount', coalesce(sum(order_amount), 0)
           ),
           coalesce(
               jsonb_agg(
                   jsonb_build_object(
                       'visit_id',      id,
                       'salesperson',   staff_brief(salesperson_id),
                       'customer_code', customer_code,
                       'customer_name', customer_name,
                       'address',       customer_address,
                       'planned',       planned,
                       'status',        status,
                       'check_in_at',   check_in_at,
                       'check_out_at',  check_out_at,
                       'minutes',       case
                                            when check_in_at is null or check_out_at is null then null
                                            else floor(extract(epoch from (check_out_at - check_in_at)) / 60)::integer
                                        end,
                       'order_amount',  order_amount
                   )
                   order by check_in_at asc nulls last
               ),
               '[]'::jsonb
           )
      into v_summary, v_rows
      from visits;

    -- Per salesperson: the supervisor's "who started when, who reached how many".
    select coalesce(jsonb_agg(m order by (m ->> 'code')), '[]'::jsonb)
      into v_members
      from (
            select jsonb_build_object(
                       'id', s.id, 'code', s.code, 'name', s.full_name,
                       'planned', (select count(*)
                                     from route_customer rc
                                     join sales_route r on r.id = rc.sales_route_id
                                    where r.salesperson_id = s.id and r.is_active and rc.is_active
                                      and on_route_day(rc.visit_weekdays, rc.visit_weeks, p_date)),
                       'visited', (select count(distinct v.customer_id) from visit v
                                    where v.salesperson_id = s.id and v.visit_date = p_date
                                      and v.check_in_at is not null),
                       'strike',  (select count(distinct o.customer_id) from sales_order o
                                    where o.salesperson_id = s.id and o.order_date = p_date
                                      and o.status <> 'cancelled'),
                       'order_amount', (select coalesce(sum(o.total_amount), 0) from sales_order o
                                    where o.salesperson_id = s.id and o.order_date = p_date
                                      and o.status <> 'cancelled'),
                       'first_check_in', (select min(v.check_in_at) from visit v
                                    where v.salesperson_id = s.id and v.visit_date = p_date),
                       'last_check_out', (select max(v.check_out_at) from visit v
                                    where v.salesperson_id = s.id and v.visit_date = p_date)
                   ) as m
              from salesperson s
             where s.id = any (v_sps)
           ) per_rep;

    return jsonb_build_object('date', p_date, 'summary', v_summary, 'rows', v_rows, 'members', v_members);
end;
$function$
;
