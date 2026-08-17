-- =============================================================================
-- Reports
--
-- Two questions a rep is actually asked, and neither is answerable from the
-- Overview tab: what did I do on this day, and where did this month's money come
-- from.
--
-- Both are assembled here rather than over the wire, for the reason
-- dashboard_overview gives: the alternative is a handful of round trips whose
-- results can disagree with each other, and a report that does not add up is
-- worse than no report.
--
-- security invoker on both, again for the same reason. Every query below is
-- unfiltered by branch, and several are unfiltered by salesperson: RLS is what
-- scopes them. Marking these definer would make the reports the one place a rep
-- could read a colleague's numbers.
--
-- Cancelled orders are excluded everywhere. They are kept as rows for audit, and
-- counting them would tell a rep they sold something they did not.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- What happened on one day
--
-- The summary counts calls the way the trade does. A "strike" is a visit that
-- produced an order; a visit that did not is not a failure worth hiding — an
-- outlet found shut or a customer who declined is still a call that was made,
-- and a report that counted only strikes would flatter nobody honestly.
--
-- `planned` counts the day's MCP stops rather than the visits: it is the
-- denominator, and using visits for both halves would make coverage always 100%.
-- -----------------------------------------------------------------------------

create or replace function report_activities(p_date date)
returns jsonb
language plpgsql
stable
security invoker
set search_path = public, pg_temp
as $$
declare
    v_sp        uuid     := current_salesperson_id();
    v_weekday   smallint := extract(isodow from p_date)::smallint;
    v_planned   integer;
    v_summary   jsonb;
    v_rows      jsonb;
begin
    if v_sp is null then
        raise exception 'no salesperson is linked to this account';
    end if;

    select count(*)
      into v_planned
      from route_customer rc
      join sales_route r on r.id = rc.sales_route_id
     where r.salesperson_id = v_sp
       and r.is_active
       and rc.is_active
       and v_weekday = any (rc.visit_weekdays);

    with visits as (
        select v.id,
               v.customer_id,
               v.status,
               v.check_in_at,
               v.check_out_at,
               c.code    as customer_code,
               c.name    as customer_name,
               c.address as customer_address,
               -- On the MCP for this weekday. An outlet the rep registered
               -- themselves is not, and neither is one visited off-route.
               exists (
                   select 1
                     from route_customer rc
                     join sales_route r on r.id = rc.sales_route_id
                    where r.salesperson_id = v_sp
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
         where v.salesperson_id = v_sp
           and v.visit_date = p_date
    )
    select jsonb_build_object(
               'planned',      v_planned,
               'visited',      count(*) filter (where check_in_at is not null),
               'unplanned',    count(*) filter (where not planned),
               'strike',       count(*) filter (where order_amount > 0),
               'non_strike',   count(*) filter (where check_in_at is not null and order_amount = 0),
               'closed',       count(*) filter (where status = 'closed'),
               'order_amount', coalesce(sum(order_amount), 0)
           ),
           coalesce(
               jsonb_agg(
                   jsonb_build_object(
                       'visit_id',     id,
                       'customer_code', customer_code,
                       'customer_name', customer_name,
                       'address',      customer_address,
                       'planned',      planned,
                       'status',       status,
                       'check_in_at',  check_in_at,
                       'check_out_at', check_out_at,
                       -- Whole minutes. Seconds in a call duration are noise a
                       -- rep would only ever round off in their head anyway.
                       'minutes',      case
                                           when check_in_at is null or check_out_at is null then null
                                           else floor(extract(epoch from (check_out_at - check_in_at)) / 60)::integer
                                       end,
                       'order_amount', order_amount
                   )
                   order by check_in_at asc nulls last
               ),
               '[]'::jsonb
           )
      into v_summary, v_rows
      from visits;

    return jsonb_build_object('date', p_date, 'summary', v_summary, 'rows', v_rows);
end;
$$;

-- -----------------------------------------------------------------------------
-- Where the month's money came from
--
-- Three cuts of one month: the total against target, then the same money split
-- by customer and by product. They are returned together because they are read
-- together — a rep looking at a gap immediately wants to know which outlet or
-- which SKU it sits in, and three endpoints would let the three disagree about
-- which month they were describing.
--
-- Quantities are in base units. A report that added two boxes to three bottles
-- would produce a number meaning nothing.
-- -----------------------------------------------------------------------------

create or replace function report_sales(p_month date)
returns jsonb
language plpgsql
stable
security invoker
set search_path = public, pg_temp
as $$
declare
    v_sp       uuid := current_salesperson_id();
    v_start    date := date_trunc('month', p_month)::date;
    v_end      date := (date_trunc('month', p_month) + interval '1 month - 1 day')::date;
    v_revenue  bigint;
    v_orders   integer;
    v_target   bigint;
    v_customers jsonb;
    v_products  jsonb;
begin
    if v_sp is null then
        raise exception 'no salesperson is linked to this account';
    end if;

    select coalesce(sum(total_amount), 0), count(*)
      into v_revenue, v_orders
      from sales_order
     where salesperson_id = v_sp
       and order_date between v_start and v_end
       and status <> 'cancelled';

    -- Null rather than zero when nothing was set: no target is not a target of
    -- nothing, and the screen says so in words.
    select revenue_target
      into v_target
      from sales_target
     where salesperson_id = v_sp
       and period_month = v_start;

    select coalesce(
               jsonb_agg(row order by (row ->> 'revenue')::bigint desc),
               '[]'::jsonb
           )
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
             where o.salesperson_id = v_sp
               and o.order_date between v_start and v_end
               and o.status <> 'cancelled'
             group by c.id, c.code, c.name
           ) per_customer;

    select coalesce(
               jsonb_agg(row order by (row ->> 'revenue')::bigint desc),
               '[]'::jsonb
           )
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
             where o.salesperson_id = v_sp
               and o.order_date between v_start and v_end
               and o.status <> 'cancelled'
             group by p.id, p.code, p.name, p.base_uom
           ) per_product;

    return jsonb_build_object(
        'month',       v_start,
        'revenue',     v_revenue,
        'order_count', v_orders,
        'target',      v_target,
        'customers',   v_customers,
        'products',    v_products
    );
end;
$$;

revoke execute on function report_activities(date) from public;
revoke execute on function report_sales(date) from public;
grant execute on function report_activities(date) to authenticated;
grant execute on function report_sales(date) to authenticated;
