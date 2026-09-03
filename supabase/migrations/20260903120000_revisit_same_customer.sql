-- =============================================================================
-- A shop may be called on more than once in a day
--
-- `unique (customer_id, salesperson_id, visit_date)` has been on `visit` since
-- the first schema, and it made the first call on a shop the only one. Once the
-- rep checked out, the card went to "Đã hoàn thành" and stayed there: no way
-- back in, and a second check-in would have failed on the constraint anyway.
--
-- That is not how the day actually goes. The owner is out and asks the rep to
-- come back after lunch; the shelf count needs redoing; the order is agreed on a
-- second pass. The legacy app allowed all of it — its guard was only
-- `custIdIncall.isEmpty`, one *open* visit at a time, which is a different rule
-- and one this build keeps (visit_one_open_per_salesperson_day, untouched here).
--
-- Everything hanging off a visit is already per-visit — stock_count,
-- display_audit, visit_feedback and visit_step_result are all unique on
-- visit_id, not on customer — so the second call starts with a clean step list
-- and cannot overwrite the first. Nothing there needs changing.
--
-- What does need changing is the two places that count calls as coverage.
-- =============================================================================

alter table visit
    drop constraint if exists visit_customer_id_salesperson_id_visit_date_key;

-- -----------------------------------------------------------------------------
-- Coverage counts shops, not calls
--
-- Both figures below are read against a denominator of MCP stops, which is a
-- count of customers. While a customer could be visited only once the two agreed
-- by construction; now they do not, and a rep who doubles back would be reported
-- as having covered more of the route than the route contains.
--
-- Only the aggregation changes in each. Postgres has no way to amend one
-- statement of a function, so both are restated whole.
-- -----------------------------------------------------------------------------

create or replace function dashboard_overview(p_date date)
returns jsonb
language plpgsql
stable
security invoker
set search_path = public, pg_temp
as $$
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
       and v_weekday = any (rc.visit_weekdays);

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
$$;

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
               -- Every count below is of shops, not of calls. A rep may call on
               -- the same shop twice in a day, and all five are read against
               -- `planned`, which counts MCP stops.
               'visited',      count(distinct customer_id) filter (where check_in_at is not null),
               'unplanned',    count(distinct customer_id) filter (where not planned),
               'strike',       count(distinct customer_id) filter (where order_amount > 0),
               -- Subtraction rather than its own filter. A shop that declined on
               -- the first call and bought on the second is a strike, not both —
               -- a per-row filter would have counted it in each.
               'non_strike',   count(distinct customer_id) filter (where check_in_at is not null)
                               - count(distinct customer_id) filter (where order_amount > 0),
               'closed',       count(distinct customer_id) filter (where status = 'closed'),
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
