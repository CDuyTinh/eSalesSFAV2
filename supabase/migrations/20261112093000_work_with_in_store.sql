-- =============================================================================
-- eSalesManager G7 — the in-store steps of a work-with visit
--
-- Legacy STOCK_OUTLET, TAKE_ORDER, MARKET_INFO and POSM_STATUS on the
-- supervisor's own call. Stock counts, competitor surveys and POSM checks go
-- through the SFA app's own functions unchanged: each wants the visit to be the
-- caller's, which a work-with visit is, and records the supervisor as who did
-- it. The order goes through mgr_submit_order (20261112090000) so it is the
-- rep's.
--
-- Here: the priced product list a count or an order is filled from, a summary
-- of what the visit has done so far, and the legacy's minimum time at the
-- outlet before a work-with call may be closed
-- (API_CheckOutValidateTimeRequired_WorkWith).
-- =============================================================================

insert into app_setting (key, value, description)
values ('work_with_min_minutes', '5', 'Phút tối thiểu ở điểm bán trước khi giám sát check-out lượt đi tuyến cùng; 0 = không giới hạn')
on conflict (key) do nothing;

/** The caller's own open-or-closed work-with visit, or a refusal. Internal. */
create or replace function own_work_with_visit(p_visit_id uuid)
returns visit
language plpgsql
stable
security definer
set search_path = public, pg_temp
as $$
declare
    v visit;
begin
    select * into v from visit
    where id = p_visit_id and salesperson_id = current_salesperson_id()
      and work_with_salesperson_id is not null;
    if not found then
        raise exception 'không tìm thấy lượt đi tuyến cùng của bạn';
    end if;
    return v;
end;
$$;

/**
 * Every active product with its sale units priced for this outlet's class
 * today (class price first, list price otherwise — submit_order's rule), the
 * outlet's previous count and what this visit has counted so far.
 */
create or replace function mgr_visit_products(p_visit_id uuid)
returns jsonb
language plpgsql
stable
security definer
set search_path = public, pg_temp
as $$
declare
    v       visit := own_work_with_visit(p_visit_id);
    v_class uuid  := (select class_id from customer where id = v.customer_id);
    v_prev  uuid  := (select sc.id from stock_count sc
                      where sc.customer_id = v.customer_id and sc.visit_id <> v.id
                      order by sc.count_date desc, sc.created_at desc limit 1);
    v_mine  uuid  := (select sc.id from stock_count sc where sc.visit_id = v.id);
begin
    return jsonb_build_object(
        'customer_id', v.customer_id,
        'previous_count_date', (select count_date from stock_count where id = v_prev),
        'products', coalesce((
            select jsonb_agg(jsonb_build_object(
                'id', p.id, 'code', p.code, 'name', p.name, 'base_uom', p.base_uom,
                'category', pc.name,
                'previous_base_qty', (select sum(l.base_qty) from stock_count_line l
                                      where l.stock_count_id = v_prev and l.product_id = p.id),
                'units', (
                    select coalesce(jsonb_agg(jsonb_build_object(
                        'uom_code', pu.uom_code,
                        'uom_name', coalesce(u.name, pu.uom_code),
                        'conversion_rate', pu.conversion_rate,
                        'price', (select pl.price from price_list pl
                                  where pl.product_id = p.id and pl.uom_code = pu.uom_code
                                    and (pl.class_id = v_class or pl.class_id is null)
                                    and v.visit_date between pl.from_date and pl.to_date
                                  order by pl.class_id nulls last limit 1),
                        'counted_qty', (select l.qty from stock_count_line l
                                        where l.stock_count_id = v_mine and l.product_id = p.id
                                          and l.uom_code = pu.uom_code)
                    ) order by pu.conversion_rate desc), '[]'::jsonb)
                    from product_uom pu left join uom u on u.code = pu.uom_code
                    where pu.product_id = p.id)
            ) order by pc.sort_order nulls last, p.code)
            from product p
            left join product_category pc on pc.id = p.category_id
            where p.is_active
        ), '[]'::jsonb)
    );
end;
$$;

/** What the supervisor has done on this call so far, for the visit screen. */
create or replace function mgr_visit_tasks(p_visit_id uuid)
returns jsonb
language plpgsql
stable
security definer
set search_path = public, pg_temp
as $$
declare
    v visit := own_work_with_visit(p_visit_id);
begin
    return jsonb_build_object(
        'stock_count', (select jsonb_build_object('at', sc.created_at, 'lines', sc.line_count)
                        from stock_count sc where sc.visit_id = v.id),
        'orders', coalesce((select jsonb_agg(jsonb_build_object(
                        'id', o.id, 'order_no', o.order_no, 'total_amount', o.total_amount,
                        'discount_amount', o.discount_amount, 'created_at', o.created_at)
                        order by o.created_at)
                    from sales_order o where o.visit_id = v.id and o.status <> 'cancelled'), '[]'::jsonb),
        'posm_checked', (select count(*) from posm_check where visit_id = v.id),
        'surveys_done', (select count(distinct survey_id) from competitor_survey_result where visit_id = v.id),
        'min_minutes', coalesce((select value::integer from app_setting where key = 'work_with_min_minutes'), 0)
    );
end;
$$;

create or replace function mgr_work_with_check_out(
    p_visit_id uuid,
    p_note     text default null,
    p_lat      double precision default null,
    p_lng      double precision default null
)
returns uuid
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
    v_visit visit;
    v_min   integer := coalesce((select value::integer from app_setting where key = 'work_with_min_minutes'), 0);
begin
    select * into v_visit from visit where id = p_visit_id for update;
    if v_visit.id is null or v_visit.salesperson_id <> current_salesperson_id() then
        raise exception 'không tìm thấy lượt viếng thăm của bạn';
    end if;
    if v_visit.status <> 'in_progress' then
        raise exception 'lượt viếng thăm này đã kết thúc';
    end if;
    -- A work-with call is coaching; leaving within a minute or two is not one.
    if v_min > 0 and v_visit.check_in_at is not null
       and now() < v_visit.check_in_at + make_interval(mins => v_min) then
        raise exception 'cần ở điểm bán ít nhất % phút (còn % phút)', v_min,
            ceil(extract(epoch from (v_visit.check_in_at + make_interval(mins => v_min) - now())) / 60)::integer;
    end if;

    update visit
       set status        = 'completed',
           check_out_at  = now(),
           check_out_lat = p_lat,
           check_out_lng = p_lng,
           note          = coalesce(nullif(btrim(coalesce(p_note, '')), ''), note)
     where id = p_visit_id;

    return p_visit_id;
end;
$$;

revoke execute on function own_work_with_visit(uuid) from public, anon, authenticated;
revoke execute on function mgr_visit_products(uuid) from public, anon;
revoke execute on function mgr_visit_tasks(uuid) from public, anon;
grant execute on function mgr_visit_products(uuid) to authenticated;
grant execute on function mgr_visit_tasks(uuid) to authenticated;
