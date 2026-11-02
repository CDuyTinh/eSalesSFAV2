-- =============================================================================
-- eSalesManager — Chỉ tiêu nhân viên
--
-- A rep's month: the overall revenue and order targets (sales_target) and the
-- finer ones by product group and by product (sales_target_product). The legacy
-- set these in head-office screens; here an AM or RM sets them for the reps
-- under them in the manager app, and an SS reads them.
--
-- Past months are closed: a target changed after the month is over rewrites
-- the record of what was asked rather than what will be.
-- =============================================================================

create or replace function mgr_targets(p_month date, p_salesperson_id uuid)
returns jsonb
language plpgsql
stable
security definer
set search_path = public, pg_temp
as $$
declare
    v_month date := date_trunc('month', p_month)::date;
begin
    if not is_team_member(p_salesperson_id)
       or (select position from salesperson where id = p_salesperson_id) <> 'sr' then
        raise exception 'nhân viên không thuộc nhóm của bạn';
    end if;

    return jsonb_build_object(
        'month', v_month,
        'can_edit', (select position in ('am', 'rm') from salesperson where id = current_salesperson_id())
                    and v_month >= date_trunc('month', (now() at time zone 'Asia/Ho_Chi_Minh'))::date,
        'revenue_target', (select revenue_target from sales_target
                            where salesperson_id = p_salesperson_id and period_month = v_month),
        'order_target', (select order_target from sales_target
                          where salesperson_id = p_salesperson_id and period_month = v_month),
        -- Every active group, with its target or null, so each has a field.
        'categories', coalesce((
            select jsonb_agg(jsonb_build_object(
                'id', c.id, 'code', c.code, 'name', c.name,
                'target', (select t.target_amount from sales_target_product t
                            where t.salesperson_id = p_salesperson_id and t.period_month = v_month
                              and t.category_id = c.id)
            ) order by c.sort_order, c.name)
            from product_category c
            where c.is_active
        ), '[]'::jsonb),
        -- Every active product, with its target or null: the screen lists the
        -- ones that have one and offers the rest to add.
        'products', coalesce((
            select jsonb_agg(jsonb_build_object(
                'id', p.id, 'code', p.code, 'name', p.name,
                'target', (select t.target_amount from sales_target_product t
                            where t.salesperson_id = p_salesperson_id and t.period_month = v_month
                              and t.product_id = p.id)
            ) order by p.code)
            from product p
            where p.is_active
        ), '[]'::jsonb)
    );
end;
$$;

/**
 * payload: { salesperson_id, month, revenue_target?, order_target?,
 *            categories: [{ id, target }], products: [{ id, target }] }
 * Replaces the rep's month: a group or product left out, or with no target,
 * has none afterwards. No overall figures removes the overall row.
 */
create or replace function mgr_targets_save(payload jsonb)
returns void
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
    v_sp      uuid := (payload ->> 'salesperson_id')::uuid;
    v_month   date := date_trunc('month', (payload ->> 'month')::date)::date;
    v_revenue bigint := (payload ->> 'revenue_target')::bigint;
    v_orders  integer := (payload ->> 'order_target')::integer;
begin
    if (select position from salesperson where id = current_salesperson_id()) not in ('am', 'rm') then
        raise exception 'chỉ quản lý khu vực / vùng được đặt chỉ tiêu';
    end if;
    if v_sp is null or not is_team_member(v_sp)
       or (select position from salesperson where id = v_sp) <> 'sr' then
        raise exception 'nhân viên không thuộc nhóm của bạn';
    end if;
    if v_month is null then
        raise exception 'chọn tháng';
    end if;
    if v_month < date_trunc('month', (now() at time zone 'Asia/Ho_Chi_Minh'))::date then
        raise exception 'tháng đã qua, không sửa chỉ tiêu được';
    end if;
    if coalesce(v_revenue, 0) < 0 or coalesce(v_orders, 0) < 0
       or exists (select 1 from jsonb_array_elements(coalesce(payload -> 'categories', '[]')) e
                  where (e ->> 'target')::bigint < 0)
       or exists (select 1 from jsonb_array_elements(coalesce(payload -> 'products', '[]')) e
                  where (e ->> 'target')::bigint < 0) then
        raise exception 'chỉ tiêu không được âm';
    end if;

    if coalesce(v_revenue, 0) = 0 and coalesce(v_orders, 0) = 0 then
        delete from sales_target where salesperson_id = v_sp and period_month = v_month;
    else
        insert into sales_target (salesperson_id, period_month, revenue_target, order_target)
        values (v_sp, v_month, coalesce(v_revenue, 0), coalesce(v_orders, 0))
        on conflict (salesperson_id, period_month)
        do update set revenue_target = excluded.revenue_target, order_target = excluded.order_target;
    end if;

    delete from sales_target_product where salesperson_id = v_sp and period_month = v_month;

    insert into sales_target_product (salesperson_id, period_month, category_id, target_amount)
    select v_sp, v_month, (e ->> 'id')::uuid, (e ->> 'target')::bigint
    from jsonb_array_elements(coalesce(payload -> 'categories', '[]')) e
    where coalesce((e ->> 'target')::bigint, 0) > 0;

    insert into sales_target_product (salesperson_id, period_month, product_id, target_amount)
    select v_sp, v_month, (e ->> 'id')::uuid, (e ->> 'target')::bigint
    from jsonb_array_elements(coalesce(payload -> 'products', '[]')) e
    where coalesce((e ->> 'target')::bigint, 0) > 0;
end;
$$;

revoke execute on function mgr_targets(date, uuid) from public, anon;
revoke execute on function mgr_targets_save(jsonb) from public, anon;
grant execute on function mgr_targets(date, uuid) to authenticated;
grant execute on function mgr_targets_save(jsonb) to authenticated;
