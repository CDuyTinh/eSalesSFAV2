-- =============================================================================
-- The fields the legacy report items show and these reports did not return:
--
--   orders     qty_total       every line's quantity in the base unit (the
--                              legacy "Tổng số lượng")
--   sales      pending_revenue  the month's, each customer's: revenue on orders
--              pending_qty      still awaiting confirmation (no delivery status
--                              exists here, so this stands in for the legacy
--                              "chưa giao")
--              avatar_url / image_url  the customer's and the product's picture
--   sku        image_url
--   trade      avatar_url
--
-- Pictures are passed through as stored: null today for every row, and the
-- app keeps its placeholder until head office loads them.
-- =============================================================================

create or replace function report_sales_of(p_sps uuid[], p_month date)
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
    v_pending  bigint;
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

    select coalesce(sum(total_amount), 0)
      into v_pending
      from sales_order
     where salesperson_id = any (v_sps)
       and order_date between v_start and v_end
       and status = 'new';

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
                       'avatar_url',    c.avatar_url,
                       'orders',        count(distinct o.id),
                       'revenue',       sum(o.total_amount),
                       'pending_revenue', coalesce(sum(o.total_amount) filter (where o.status = 'new'), 0)
                   ) as row
              from sales_order o
              join customer c on c.id = o.customer_id
             where o.salesperson_id = any (v_sps)
               and o.order_date between v_start and v_end
               and o.status <> 'cancelled'
             group by c.id, c.code, c.name, c.avatar_url
           ) per_customer;

    select coalesce(jsonb_agg(row order by (row ->> 'revenue')::bigint desc), '[]'::jsonb)
      into v_products
      from (
            select jsonb_build_object(
                       'product_code', p.code,
                       'product_name', p.name,
                       'base_uom',     p.base_uom,
                       'image_url',    p.image_url,
                       'base_qty',     sum(l.base_qty),
                       'pending_qty',  coalesce(sum(l.base_qty) filter (where o.status = 'new'), 0),
                       'revenue',      sum(l.line_amount)
                   ) as row
              from sales_order o
              join sales_order_line l on l.order_id = o.id
              join product p on p.id = l.product_id
             where o.salesperson_id = any (v_sps)
               and o.order_date between v_start and v_end
               and o.status <> 'cancelled'
             group by p.id, p.code, p.name, p.base_uom, p.image_url
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
        'pending_revenue', v_pending,
        'customers',   v_customers,
        'products',    v_products,
        'members',     v_members
    );
end;
$$;


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
              where l.order_id = o.id and not l.is_free) as line_count,
            (select coalesce(sum(l.base_qty), 0) from sales_order_line l
              where l.order_id = o.id) as qty_total
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
                'qty_total', qty_total,
                'sub_total', sub_total,
                'discount_amount', discount_amount,
                'vat_amount', vat_amount,
                'total_amount', total_amount
            ) order by order_date desc, created_at desc, id)
            from page
        ), '[]'::jsonb)
    );
$$;

create or replace function report_trade_of(
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
            c.avatar_url,
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
            c.code, c.name, c.avatar_url, p.code, p.name, lv.name
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
                'avatar_url', avatar_url,
                'program_code', program_code,
                'program_name', program_name,
                'level_name', level_name
            ) order by registered_at desc, customer_name)
            from signed
        ), '[]'::jsonb)
    );
$$;

-- report_sku returns a table, so a new column means dropping and recreating
-- both, and granting them again as they were.
drop function report_sku(date, date, text);
drop function report_sku_of(uuid[], date, date, text);

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
    image_url    text,
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
        p.image_url,
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
    group by p.id, p.code, p.name, p.base_uom, p.image_url
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
    image_url    text,
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

revoke execute on function report_sku_of(uuid[], date, date, text) from public, anon;
grant execute on function report_sku_of(uuid[], date, date, text) to authenticated;
