-- =============================================================================
-- Báo cáo — one definition for the rep and for the supervisor
--
-- SFA has six reports, each written for "the signed-in rep":
--   report_activities, report_sales, report_orders, report_sku, report_trade,
--   report_work_result.
-- The eSalesManager report tab asks the same six questions of a team or of one
-- rep in it (legacy ReportController: API_GetRPT_DailyActivitySumary_Manager,
-- API_GetRPT_Monthly*_eSalesManager, API_GetReportOrderStatus_eSalesManager,
-- API_FetchReportSKUManager, API_GetEmployeeWorkResultManager…).
--
-- Copying the six into mgr_* versions would be the start of two sets of numbers
-- that drift. So each body moves, unchanged in what it counts, into a core
-- function that takes the salespeople as an array — `report_x_of(p_sps, …)` —
-- and the SFA function becomes a one-line call with the caller alone. The rep's
-- screens get the same answer as before; the supervisor's screens get the same
-- answer the rep would.
--
-- The cores are security definer and guard themselves through
-- readable_salespeople(): every id must be the caller or someone in the
-- caller's team. That is the whole of the access rule; it replaces the RLS the
-- invoker versions leaned on, which only ever narrowed to the rep's own rows
-- anyway.
--
-- Additions, all additive to the JSON so older SFA builds ignore them: rows
-- name their salesperson, and the activities and sales reports carry a
-- per-salesperson breakdown for the supervisor's team view.
-- =============================================================================

create function readable_salespeople(p_sps uuid[])
returns uuid[]
language plpgsql
stable
security definer
set search_path = public, pg_temp
as $$
declare
    v_me uuid := current_salesperson_id();
    v_id uuid;
begin
    if v_me is null then
        raise exception 'no salesperson is linked to this account';
    end if;
    if p_sps is null or cardinality(p_sps) = 0 then
        raise exception 'chưa chọn nhân viên';
    end if;
    foreach v_id in array p_sps loop
        if v_id is null or (v_id <> v_me and not is_team_member(v_id)) then
            raise exception 'nhân viên không thuộc nhóm của bạn';
        end if;
    end loop;
    return p_sps;
end;
$$;

-- =============================================================================
-- Hoạt động trong ngày
-- =============================================================================

create function report_activities_of(p_sps uuid[], p_date date)
returns jsonb
language plpgsql
stable
security definer
set search_path = public, pg_temp
as $$
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
       and v_weekday = any (rc.visit_weekdays);

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
                      and v_weekday = any (rc.visit_weekdays)
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
                                      and v_weekday = any (rc.visit_weekdays)),
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
$$;

create or replace function report_activities(p_date date)
returns jsonb
language sql
stable
security invoker
set search_path = public, pg_temp
as $$
    select report_activities_of(array[current_salesperson_id()], p_date);
$$;

-- =============================================================================
-- Doanh số tháng (MTD)
-- =============================================================================

create function report_sales_of(p_sps uuid[], p_month date)
returns jsonb
language plpgsql
stable
security definer
set search_path = public, pg_temp
as $$
declare
    v_sps      uuid[] := readable_salespeople(p_sps);
    v_start    date := date_trunc('month', p_month)::date;
    v_end      date := (date_trunc('month', p_month) + interval '1 month - 1 day')::date;
    v_revenue  bigint;
    v_orders   integer;
    v_target   bigint;
    v_customers jsonb;
    v_products  jsonb;
    v_members   jsonb;
begin
    select coalesce(sum(total_amount), 0), count(*)
      into v_revenue, v_orders
      from sales_order
     where salesperson_id = any (v_sps)
       and order_date between v_start and v_end
       and status <> 'cancelled';

    -- Null rather than zero when nothing was set for anyone.
    select sum(revenue_target)
      into v_target
      from sales_target
     where salesperson_id = any (v_sps)
       and period_month = v_start;

    select coalesce(jsonb_agg(row order by (row ->> 'revenue')::bigint desc), '[]'::jsonb)
      into v_customers
      from (
            select jsonb_build_object(
                       'customer_code', c.code,
                       'customer_name', c.name,
                       'orders',        count(distinct o.id),
                       'revenue',       sum(o.total_amount)
                   ) as row
              from sales_order o
              join customer c on c.id = o.customer_id
             where o.salesperson_id = any (v_sps)
               and o.order_date between v_start and v_end
               and o.status <> 'cancelled'
             group by c.id, c.code, c.name
           ) per_customer;

    select coalesce(jsonb_agg(row order by (row ->> 'revenue')::bigint desc), '[]'::jsonb)
      into v_products
      from (
            select jsonb_build_object(
                       'product_code', p.code,
                       'product_name', p.name,
                       'base_uom',     p.base_uom,
                       'base_qty',     sum(l.base_qty),
                       'revenue',      sum(l.line_amount)
                   ) as row
              from sales_order o
              join sales_order_line l on l.order_id = o.id
              join product p on p.id = l.product_id
             where o.salesperson_id = any (v_sps)
               and o.order_date between v_start and v_end
               and o.status <> 'cancelled'
             group by p.id, p.code, p.name, p.base_uom
           ) per_product;

    select coalesce(jsonb_agg(row order by (row ->> 'revenue')::bigint desc), '[]'::jsonb)
      into v_members
      from (
            select jsonb_build_object(
                       'id', s.id, 'code', s.code, 'name', s.full_name,
                       'orders', (select count(*) from sales_order o
                                   where o.salesperson_id = s.id and o.order_date between v_start and v_end
                                     and o.status <> 'cancelled'),
                       'revenue', (select coalesce(sum(o.total_amount), 0) from sales_order o
                                   where o.salesperson_id = s.id and o.order_date between v_start and v_end
                                     and o.status <> 'cancelled'),
                       'target', (select t.revenue_target from sales_target t
                                   where t.salesperson_id = s.id and t.period_month = v_start)
                   ) as row
              from salesperson s
             where s.id = any (v_sps)
           ) per_rep;

    return jsonb_build_object(
        'month',       v_start,
        'revenue',     v_revenue,
        'order_count', v_orders,
        'target',      v_target,
        'customers',   v_customers,
        'products',    v_products,
        'members',     v_members
    );
end;
$$;

create or replace function report_sales(p_month date)
returns jsonb
language sql
stable
security invoker
set search_path = public, pg_temp
as $$
    select report_sales_of(array[current_salesperson_id()], p_month);
$$;

-- =============================================================================
-- Đơn hàng
-- =============================================================================

create function report_orders_of(
    p_sps       uuid[],
    p_from_date date default (current_date - 30),
    p_to_date   date default current_date,
    p_statuses  order_status[] default null,
    p_in_route  boolean default null,
    p_limit     integer default 50,
    p_offset    integer default 0
)
returns jsonb
language sql
stable
security definer
set search_path = public, pg_temp
as $$
    with mine as (
        select
            o.*,
            c.code as customer_code,
            c.name as customer_name,
            o.visit_id is not null as in_route,
            (select count(*) from sales_order_line l
              where l.order_id = o.id and not l.is_free) as line_count
        from sales_order o
        join customer c on c.id = o.customer_id
        where o.salesperson_id = any (readable_salespeople(p_sps))
          and o.order_date between p_from_date and p_to_date
    ),
    scoped as (
        select * from mine
        where p_in_route is null or in_route = p_in_route
    ),
    filtered as (
        select * from scoped
        where p_statuses is null or status = any (p_statuses)
    ),
    page as (
        select * from filtered
        order by order_date desc, created_at desc
        limit greatest(p_limit, 1) offset greatest(p_offset, 0)
    )
    select jsonb_build_object(
        'total', (select count(*) from filtered),
        'total_amount', coalesce((
            select sum(total_amount) from filtered where status <> 'cancelled'
        ), 0),
        'counts', coalesce((
            select jsonb_object_agg(status, n)
            from (select status, count(*) as n from scoped group by status) s
        ), '{}'::jsonb),
        'items', coalesce((
            select jsonb_agg(jsonb_build_object(
                'id', id,
                'order_no', order_no,
                'order_date', order_date,
                'salesperson', staff_brief(salesperson_id),
                'customer_code', customer_code,
                'customer_name', customer_name,
                'status', status,
                'in_route', in_route,
                'line_count', line_count,
                'sub_total', sub_total,
                'discount_amount', discount_amount,
                'vat_amount', vat_amount,
                'total_amount', total_amount
            ) order by order_date desc, created_at desc)
            from page
        ), '[]'::jsonb)
    );
$$;

create or replace function report_orders(
    p_from_date date default (current_date - 30),
    p_to_date   date default current_date,
    p_statuses  order_status[] default null,
    p_in_route  boolean default null,
    p_limit     integer default 50,
    p_offset    integer default 0
)
returns jsonb
language sql
stable
security invoker
set search_path = public, pg_temp
as $$
    select report_orders_of(array[current_salesperson_id()], p_from_date, p_to_date,
                            p_statuses, p_in_route, p_limit, p_offset);
$$;

-- =============================================================================
-- SKU
-- =============================================================================

create function report_sku_of(
    p_sps       uuid[],
    p_from_date date default (current_date - 30),
    p_to_date   date default current_date,
    p_query     text default null
)
returns table (
    product_id   uuid,
    product_code text,
    product_name text,
    stock_uom    text,
    qty_confirmed integer,
    qty_pending   integer,
    qty_total     integer,
    amount_total  bigint
)
language sql
stable
security definer
set search_path = public, pg_temp
as $$
    select
        p.id,
        p.code,
        p.name,
        p.base_uom,
        coalesce(sum(l.base_qty) filter (where o.status = 'confirmed'), 0)::integer,
        coalesce(sum(l.base_qty) filter (where o.status = 'new'), 0)::integer,
        coalesce(sum(l.base_qty), 0)::integer,
        coalesce(sum(l.line_amount) filter (where o.status <> 'cancelled'), 0)::bigint
    from sales_order o
    join sales_order_line l on l.order_id = o.id and not l.is_free
    join product p on p.id = l.product_id
    where o.salesperson_id = any (readable_salespeople(p_sps))
      and o.order_date between p_from_date and p_to_date
      and o.status <> 'cancelled'
      and (
          p_query is null
          or btrim(p_query) = ''
          or p.name ilike '%' || btrim(p_query) || '%'
          or p.code ilike '%' || btrim(p_query) || '%'
      )
    group by p.id, p.code, p.name, p.base_uom
    having coalesce(sum(l.base_qty), 0) <> 0
    order by coalesce(sum(l.base_qty), 0) desc, p.code;
$$;

create or replace function report_sku(
    p_from_date date default (current_date - 30),
    p_to_date   date default current_date,
    p_query     text default null
)
returns table (
    product_id   uuid,
    product_code text,
    product_name text,
    stock_uom    text,
    qty_confirmed integer,
    qty_pending   integer,
    qty_total     integer,
    amount_total  bigint
)
language sql
stable
security invoker
set search_path = public, pg_temp
as $$
    select * from report_sku_of(array[current_salesperson_id()], p_from_date, p_to_date, p_query);
$$;

-- =============================================================================
-- Chương trình (display + loyalty sign-ups)
-- =============================================================================

create function report_trade_of(
    p_sps       uuid[],
    p_from_date date default (current_date - 30),
    p_to_date   date default current_date
)
returns jsonb
language sql
stable
security definer
set search_path = public, pg_temp
as $$
    with ids as (select unnest(readable_salespeople(p_sps)) as id),
    signed as (
        select
            'display' as kind,
            d.id,
            d.salesperson_id,
            d.registered_at,
            d.status,
            coalesce(d.portion, 1) as portion,
            c.code as customer_code,
            c.name as customer_name,
            p.code as program_code,
            p.name as program_name,
            lv.name as level_name
        from display_registration d
        join customer c on c.id = d.customer_id
        join display_program p on p.id = d.program_id
        left join display_program_level lv on lv.id = d.level_id
        where d.salesperson_id in (select id from ids)
          and d.registered_at between p_from_date and p_to_date

        union all

        select
            'loyalty', l.id, l.salesperson_id, l.registered_at, l.status, coalesce(l.portion, 1),
            c.code, c.name, p.code, p.name, lv.name
        from loyalty_registration l
        join customer c on c.id = l.customer_id
        join loyalty_program p on p.id = l.program_id
        left join loyalty_program_level lv on lv.id = l.level_id
        where l.salesperson_id in (select id from ids)
          and l.registered_at between p_from_date and p_to_date
    )
    select jsonb_build_object(
        'display_count', (select count(*) from signed where kind = 'display'),
        'loyalty_count', (select count(*) from signed where kind = 'loyalty'),
        'customer_count', (select count(distinct customer_code) from signed),
        'items', coalesce((
            select jsonb_agg(jsonb_build_object(
                'kind', kind,
                'salesperson', staff_brief(salesperson_id),
                'registered_at', registered_at,
                'status', status,
                'portion', portion,
                'customer_code', customer_code,
                'customer_name', customer_name,
                'program_code', program_code,
                'program_name', program_name,
                'level_name', level_name
            ) order by registered_at desc, customer_name)
            from signed
        ), '[]'::jsonb)
    );
$$;

create or replace function report_trade(
    p_from_date date default (current_date - 30),
    p_to_date   date default current_date
)
returns jsonb
language sql
stable
security invoker
set search_path = public, pg_temp
as $$
    select report_trade_of(array[current_salesperson_id()], p_from_date, p_to_date);
$$;

-- =============================================================================
-- Kết quả công việc
-- =============================================================================

create function report_work_result_of(
    p_sps          uuid[],
    p_from_date    date default (current_date - 30),
    p_to_date      date default current_date,
    p_category_ids uuid[] default null
)
returns jsonb
language sql
stable
security definer
set search_path = public, pg_temp
as $$
    with finished as (
        select
            p.id,
            p.salesperson_id,
            p.content,
            p.result,
            p.reason,
            p.status,
            p.from_at,
            p.to_at,
            c.name as category_name,
            (select count(*) from work_plan_object o where o.plan_id = p.id) as object_count
        from work_plan p
        join work_with_category c on c.id = p.category_id
        where p.salesperson_id = any (readable_salespeople(p_sps))
          and p.status in ('completed', 'cancelled')
          and (p.from_at at time zone 'Asia/Ho_Chi_Minh')::date <= p_to_date
          and (p.to_at   at time zone 'Asia/Ho_Chi_Minh')::date >= p_from_date
          and (p_category_ids is null or p.category_id = any (p_category_ids))
    )
    select jsonb_build_object(
        'completed', (select count(*) from finished where status = 'completed'),
        'cancelled', (select count(*) from finished where status = 'cancelled'),
        'total', (select count(*) from finished),
        'items', coalesce((
            select jsonb_agg(jsonb_build_object(
                'id', id,
                'salesperson', staff_brief(salesperson_id),
                'content', content,
                'category_name', category_name,
                'status', status,
                'from_at', from_at,
                'to_at', to_at,
                'object_count', object_count,
                'outcome', coalesce(result, reason)
            ) order by from_at desc)
            from finished
        ), '[]'::jsonb)
    );
$$;

create or replace function report_work_result(
    p_from_date    date default (current_date - 30),
    p_to_date      date default current_date,
    p_category_ids uuid[] default null
)
returns jsonb
language sql
stable
security invoker
set search_path = public, pg_temp
as $$
    select report_work_result_of(array[current_salesperson_id()], p_from_date, p_to_date, p_category_ids);
$$;

-- -----------------------------------------------------------------------------
-- Grants. The cores guard themselves; anon never needs them.
-- -----------------------------------------------------------------------------

revoke execute on function readable_salespeople(uuid[]) from public, anon;
revoke execute on function report_activities_of(uuid[], date) from public, anon;
revoke execute on function report_sales_of(uuid[], date) from public, anon;
revoke execute on function report_orders_of(uuid[], date, date, order_status[], boolean, integer, integer) from public, anon;
revoke execute on function report_sku_of(uuid[], date, date, text) from public, anon;
revoke execute on function report_trade_of(uuid[], date, date) from public, anon;
revoke execute on function report_work_result_of(uuid[], date, date, uuid[]) from public, anon;

grant execute on function readable_salespeople(uuid[]) to authenticated;
grant execute on function report_activities_of(uuid[], date) to authenticated;
grant execute on function report_sales_of(uuid[], date) to authenticated;
grant execute on function report_orders_of(uuid[], date, date, order_status[], boolean, integer, integer) to authenticated;
grant execute on function report_sku_of(uuid[], date, date, text) to authenticated;
grant execute on function report_trade_of(uuid[], date, date) to authenticated;
grant execute on function report_work_result_of(uuid[], date, date, uuid[]) to authenticated;
