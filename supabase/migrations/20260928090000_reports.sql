-- =============================================================================
-- The four reports the rebuild was missing
--
-- The legacy report menu has six entries. Two are already here -- doanh số
-- tháng (MTD) and hoạt động ngày (daily activities). These are the other four.
--
-- Three of them are faithful ports. The fourth is not, and it is worth saying
-- why before the code says it quietly: **the trade report does not exist over
-- there.** Its Flutter bloc has the repository call commented out and yields a
-- hard-coded ReportTradeModel -- visibility 15, loyalty 110, one TradeItem
-- called "Product A". There is no endpoint, no proc, no data. What it was
-- *meant* to be is legible from those fields, and the rebuild happens to store
-- exactly that already: display registrations and loyalty registrations per
-- outlet. So this builds the report the screen was reaching for, rather than
-- porting a placeholder or pretending the gap is closed.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- Báo cáo đơn hàng. API_GetOM_SalesOrdHistory.
--
-- The rep's own orders over a window, with the per-status counts the filter
-- chips need and a total. The legacy also filters by order type and by whether
-- the order was booked on-route or off-route; the rebuild has no order types,
-- and on-route is simply whether the order carries a visit.
-- -----------------------------------------------------------------------------

create function report_orders(
    p_from_date date default (current_date - 30),
    p_to_date   date default current_date,
    p_statuses  order_status[] default null,
    /** Null is both; true is orders booked inside a visit. */
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
        where o.salesperson_id = current_salesperson_id()
          and o.order_date between p_from_date and p_to_date
    ),
    -- Counts ignore the status filter so a chip keeps its own number while
    -- another chip is the one doing the filtering.
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
        -- Cancelled orders are counted but not summed: the money never moved.
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

comment on function report_orders(date, date, order_status[], boolean, integer, integer) is
    'The rep''s orders over a window, with per-status counts. API_GetOM_SalesOrdHistory.';

-- -----------------------------------------------------------------------------
-- Báo cáo SKU. API_FetchReportSKU.
--
-- What the rep sold, per product, in stock units. The legacy splits the figure
-- three ways -- confirmed, still waiting, and the sum -- because a rep is
-- measured on what was booked but paid on what was approved.
--
-- Free lines are excluded, as over there: a case given away under a promotion
-- is not a case sold, and counting it would flatter every SKU that happens to
-- be a reward. Cancelled orders drop out entirely.
-- -----------------------------------------------------------------------------

create function report_sku(
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
    select
        p.id,
        p.code,
        p.name,
        p.base_uom,
        -- Summed in base units, so a case of twenty-four and twenty-four
        -- singles are one number rather than two incomparable ones.
        coalesce(sum(l.base_qty) filter (where o.status = 'confirmed'), 0)::integer,
        coalesce(sum(l.base_qty) filter (where o.status = 'new'), 0)::integer,
        coalesce(sum(l.base_qty), 0)::integer,
        coalesce(sum(l.line_amount) filter (where o.status <> 'cancelled'), 0)::bigint
    from sales_order o
    join sales_order_line l on l.order_id = o.id and not l.is_free
    join product p on p.id = l.product_id
    where o.salesperson_id = current_salesperson_id()
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

comment on function report_sku(date, date, text) is
    'Quantity sold per product over a window, split confirmed / pending. API_FetchReportSKU.';

-- -----------------------------------------------------------------------------
-- Báo cáo thương mại
--
-- The one with no legacy behind it. Display and loyalty registrations the rep
-- signed outlets up for, in the window -- which is what the placeholder's
-- `visibility`, `loyalty` and per-customer `registrationPortion` were reaching
-- for.
-- -----------------------------------------------------------------------------

create function report_trade(
    p_from_date date default (current_date - 30),
    p_to_date   date default current_date
)
returns jsonb
language sql
stable
security invoker
set search_path = public, pg_temp
as $$
    with signed as (
        select
            'display' as kind,
            d.id,
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
        where d.salesperson_id = current_salesperson_id()
          and d.registered_at between p_from_date and p_to_date

        union all

        select
            'loyalty', l.id, l.registered_at, l.status, coalesce(l.portion, 1),
            c.code, c.name, p.code, p.name, lv.name
        from loyalty_registration l
        join customer c on c.id = l.customer_id
        join loyalty_program p on p.id = l.program_id
        left join loyalty_program_level lv on lv.id = l.level_id
        where l.salesperson_id = current_salesperson_id()
          and l.registered_at between p_from_date and p_to_date
    )
    select jsonb_build_object(
        'display_count', (select count(*) from signed where kind = 'display'),
        'loyalty_count', (select count(*) from signed where kind = 'loyalty'),
        -- Outlets, not registrations: signing one shop up for three programmes
        -- is one shop reached.
        'customer_count', (select count(distinct customer_code) from signed),
        'items', coalesce((
            select jsonb_agg(jsonb_build_object(
                'kind', kind,
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

comment on function report_trade(date, date) is
    'Display and loyalty sign-ups the rep made in the window. No legacy equivalent — the legacy screen is hard-coded sample data.';

-- -----------------------------------------------------------------------------
-- Báo cáo kết quả công việc. API_GetReportTask.
--
-- Plans that finished, one way or the other. Over there this reads
-- OM_WorkingPlan filtered to Status '4' and '5' -- numeric codes, in the same
-- column the working-plan module fills with O/W/A/R/C/P. Two alphabets in one
-- column, which is the same divergence the plan board already collapsed; here
-- it is just `status in ('completed', 'cancelled')`.
-- -----------------------------------------------------------------------------

create function report_work_result(
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
    with finished as (
        select
            p.id,
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
        where p.salesperson_id = current_salesperson_id()
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
                'content', content,
                'category_name', category_name,
                'status', status,
                'from_at', from_at,
                'to_at', to_at,
                'object_count', object_count,
                -- One column on screen: what came of it, whichever way it went.
                'outcome', coalesce(result, reason)
            ) order by from_at desc)
            from finished
        ), '[]'::jsonb)
    );
$$;

comment on function report_work_result(date, date, uuid[]) is
    'Work plans that finished or were called off. API_GetReportTask.';
