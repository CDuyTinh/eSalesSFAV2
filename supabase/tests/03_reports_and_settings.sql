-- Report figures checked against the rows they come from, and the settings
-- and reference rows the apps rely on being present.

-- Orders: each order's total quantity is its lines' base quantities.
-- Sales: what awaits confirmation is part of what was sold, never more.
do $$
declare
    v_report jsonb;
    v_item   jsonb;
    v_row    jsonb;
begin
    perform pg_temp.act_as('nvbh01');

    v_report := report_orders(date '2026-10-01', date '2026-10-31');
    perform pg_temp.check(jsonb_array_length(v_report -> 'items') > 0, 'October has orders to check');
    for v_item in select jsonb_array_elements(v_report -> 'items') limit 10 loop
        perform pg_temp.check(
            (v_item ->> 'qty_total')::int =
            (select coalesce(sum(l.base_qty), 0) from sales_order_line l where l.order_id = (v_item ->> 'id')::uuid),
            format('order %s: qty_total is its lines'' quantity', v_item ->> 'order_no'));
    end loop;

    v_report := report_sales(date '2026-10-01');
    perform pg_temp.check((v_report ->> 'pending_revenue')::bigint <= (v_report ->> 'revenue')::bigint,
        'pending revenue is within the month''s revenue');
    perform pg_temp.check(
        (v_report ->> 'pending_revenue')::bigint =
        (select coalesce(sum(total_amount), 0) from sales_order
          where salesperson_id = pg_temp.sp('nvbh01') and status = 'new'
            and order_date between date '2026-10-01' and date '2026-10-31'),
        'pending revenue is the month''s unconfirmed orders');
    for v_row in select jsonb_array_elements(v_report -> 'customers') loop
        perform pg_temp.check((v_row ->> 'pending_revenue')::bigint <= (v_row ->> 'revenue')::bigint,
            format('customer %s: pending within revenue', v_row ->> 'customer_code'));
    end loop;
    for v_row in select jsonb_array_elements(v_report -> 'products') loop
        perform pg_temp.check((v_row ->> 'pending_qty')::int <= (v_row ->> 'base_qty')::int,
            format('product %s: pending within quantity', v_row ->> 'product_code'));
    end loop;

    -- The SKU report carries the picture column, even while it is empty.
    perform image_url from report_sku(date '2026-10-01', date '2026-10-31', null) limit 1;
end;
$$;

-- Product-group targets on the manager overview: what was sold of a group is
-- the reps' order lines in that group, free goods and cancellations left out.
do $$
declare
    v_ngk    uuid := (select id from product_category where code = 'NGK');
    v_actual bigint;
    v_expect bigint;
begin
    perform pg_temp.act_as('amkv01');
    select (t ->> 'actual')::bigint into v_actual
      from jsonb_array_elements(mgr_dashboard(date '2026-10-31') -> 'product_targets') t
     where (t ->> 'id')::uuid = v_ngk;
    perform pg_temp.check(v_actual is not null, 'the NGK group target is listed for October');

    perform pg_temp.act_as_admin();
    select coalesce(sum(l.line_amount), 0) into v_expect
      from sales_order o
      join sales_order_line l on l.order_id = o.id and not l.is_free
      join product p on p.id = l.product_id and p.category_id = v_ngk
     where o.status <> 'cancelled'
       and o.order_date between date '2026-10-01' and date '2026-10-31'
       and o.salesperson_id in (select salesperson_id from sales_target_product
                                 where period_month = date '2026-10-01' and category_id = v_ngk);
    perform pg_temp.check(v_actual = v_expect, format('NGK sold %s, expected %s', v_actual, v_expect));
end;
$$;

-- What the apps read at start-up: the settings they compare against and the
-- refusal reasons the manager's dialog offers.
do $$
declare
    k text;
begin
    foreach k in array array[
        'sfa_version_latest', 'sfa_version_min', 'sfa_update_url', 'sfa_update_note',
        'manager_version_latest', 'manager_version_min', 'manager_update_url', 'manager_update_note',
        'dashboard_kpi_green', 'dashboard_kpi_yellow'
    ] loop
        perform pg_temp.check(exists (select 1 from app_setting where key = k), format('setting %s exists', k));
    end loop;
    perform pg_temp.check(
        (select value::int from app_setting where key = 'dashboard_kpi_yellow')
        <= (select value::int from app_setting where key = 'dashboard_kpi_green'),
        'the KPI blue threshold is not above the green one');
    perform pg_temp.check(
        (select value::int from app_setting where key = 'sfa_version_min')
        <= (select value::int from app_setting where key = 'sfa_version_latest'),
        'SFA minimum version is not above the latest');
    perform pg_temp.check(
        (select value::int from app_setting where key = 'manager_version_min')
        <= (select value::int from app_setting where key = 'manager_version_latest'),
        'Manager minimum version is not above the latest');

    perform pg_temp.act_as('gsbh01');
    perform pg_temp.check(
        (select count(*) from reason_code where kind = 'approval_reject' and is_active) > 0,
        'a manager reads the refusal reasons');
end;
$$;

do $$ begin raise exception 'ALL PASSED'; end $$;
