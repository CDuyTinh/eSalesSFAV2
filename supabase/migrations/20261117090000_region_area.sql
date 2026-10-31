-- =============================================================================
-- Vùng / khu vực / nhà phân phối, and an area manager over several of them.
--
-- The legacy filter goes region → area → distributor → supervisor → rep. The
-- supervisor tree already says who reports to whom, across distributors as
-- well as within one; what was missing is where each distributor sits. A
-- branch now belongs to an area, an area to a region, and team_reps can be
-- narrowed to a set of branches — which is what picking a region, an area or
-- one distributor resolves to on the phone.
-- =============================================================================

create table region (
    id         uuid primary key default gen_random_uuid(),
    code       text not null unique,
    name       text not null,
    is_active  boolean not null default true,
    created_at timestamptz not null default now()
);

create table area (
    id         uuid primary key default gen_random_uuid(),
    code       text not null unique,
    name       text not null,
    region_id  uuid not null references region (id),
    is_active  boolean not null default true,
    created_at timestamptz not null default now()
);

alter table branch add column area_id uuid references area (id);
create index on area (region_id);
create index on branch (area_id);

-- Reference data, as branch is: anyone signed in may read it.
alter table region enable row level security;
alter table area enable row level security;
create policy "signed-in users read regions" on region for select to authenticated using (true);
create policy "signed-in users read areas" on area for select to authenticated using (true);

-- team_reps gains the branches to keep; null keeps every branch.
drop function team_reps(uuid);

create function team_reps(p_supervisor uuid default null, p_branches uuid[] default null)
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
        where s.id in (select id from tree) and s.position = 'sr' and s.is_active
          and (p_branches is null or s.branch_id = any (p_branches));
end;
$$;

revoke execute on function team_reps(uuid, uuid[]) from public, anon;
grant execute on function team_reps(uuid, uuid[]) to authenticated;

-- The dashboard takes the same narrowing.
drop function mgr_dashboard(date, uuid, uuid);

CREATE FUNCTION public.mgr_dashboard(p_date date, p_salesperson_id uuid DEFAULT NULL::uuid, p_supervisor_id uuid DEFAULT NULL::uuid, p_branch_ids uuid[] DEFAULT NULL::uuid[])
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
$function$;

revoke execute on function mgr_dashboard(date, uuid, uuid, uuid[]) from public, anon;
grant execute on function mgr_dashboard(date, uuid, uuid, uuid[]) to authenticated;
