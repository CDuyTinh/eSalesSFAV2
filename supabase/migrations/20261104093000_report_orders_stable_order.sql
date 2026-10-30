-- =============================================================================
-- Báo cáo đơn hàng: a total order for paging
--
-- report_orders sorted by (order_date desc, created_at desc) and paged with
-- limit/offset. Orders written in one transaction share created_at, so ties
-- had no defined order: the same request could put a different order at the
-- page boundary each time, and paging could show one order twice and skip
-- another. Found while checking the team-report refactor, which returned the
-- same 82 orders and the same totals but a different 50th row. id breaks the tie.
-- =============================================================================

create or replace function report_orders_of(
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
        order by order_date desc, created_at desc, id
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
            ) order by order_date desc, created_at desc, id)
            from page
        ), '[]'::jsonb)
    );
$$;
