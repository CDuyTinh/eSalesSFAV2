-- =============================================================================
-- Overview tab: monthly targets, and one function that assembles the figures.
--
-- Everything the tab shows is derived from rows the rep's own work already
-- created — orders, visits, the route they were given. The one thing that cannot
-- be derived is what head office expects of them, so that gets a table.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- Targets
--
-- Monthly, because that is the period the legacy app reported against and the
-- period a target is actually set for. Deliberately no daily target column: the
-- old screen showed one, but dividing a month by its days invents a number
-- nobody agreed to, and a rep held to a figure head office never set is worse
-- than a rep shown no figure at all.
-- -----------------------------------------------------------------------------

create table sales_target (
    id                  uuid   primary key default gen_random_uuid(),
    salesperson_id      uuid   not null references salesperson (id) on delete cascade,

    -- Always the first of the month, enforced below, so a lookup never has to
    -- guess which day of the month a period was stored under.
    period_month        date   not null,

    revenue_target      bigint  not null default 0
        constraint revenue_target_not_negative check (revenue_target >= 0),
    order_target        integer not null default 0
        constraint order_target_not_negative check (order_target >= 0),

    created_at          timestamptz not null default now(),

    unique (salesperson_id, period_month),
    constraint period_is_first_of_month
        check (period_month = date_trunc('month', period_month)::date)
);

alter table sales_target enable row level security;

create policy sales_target_select_own
    on sales_target for select to authenticated
    using (salesperson_id = current_salesperson_id());

-- -----------------------------------------------------------------------------
-- The overview itself
--
-- One function rather than the six round trips the screen would otherwise make,
-- and the aggregation happens next to the rows instead of over the wire.
--
-- security invoker on purpose: every read below is unfiltered by salesperson
-- except where a figure genuinely needs it, and RLS is what keeps one rep out of
-- another's numbers. Marking this definer would quietly turn the dashboard into
-- the one hole in that.
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
    select count(*)
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

-- -----------------------------------------------------------------------------
-- One day per point, including the days with no orders.
--
-- generate_series drives the result rather than the orders table: a week with
-- sales on Monday and Friday only must still plot seven points, or the line
-- silently redraws itself as a shorter week.
-- -----------------------------------------------------------------------------

create or replace function daily_sales_series(
    p_salesperson uuid,
    p_from        date,
    p_to          date,
    p_by_day_of_month boolean
)
returns jsonb
language sql
stable
security invoker
set search_path = public, pg_temp
as $$
    select coalesce(
        jsonb_agg(
            jsonb_build_object('title', point.title, 'actual', point.actual)
            order by point.day
        ),
        '[]'::jsonb
    )
    from (
        select d::date as day,
               case
                   when p_by_day_of_month then to_char(d, 'FMDD')
                   when extract(isodow from d) = 7 then 'CN'
                   else 'T' || (extract(isodow from d)::integer + 1)
               end as title,
               coalesce(sales.amount, 0) as actual
          from generate_series(p_from, p_to, interval '1 day') as d
          left join (
                select order_date, sum(total_amount) as amount
                  from sales_order
                 where salesperson_id = p_salesperson
                   and order_date between p_from and p_to
                   and status <> 'cancelled'
                 group by order_date
               ) as sales on sales.order_date = d::date
    ) as point;
$$;

revoke execute on function dashboard_overview(date) from public;
revoke execute on function daily_sales_series(uuid, date, date, boolean) from public;
grant execute on function dashboard_overview(date) to authenticated;
grant execute on function daily_sales_series(uuid, date, date, boolean) to authenticated;
