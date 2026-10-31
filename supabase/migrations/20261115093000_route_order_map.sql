-- =============================================================================
-- eSalesManager G8 — thứ tự ghé và bản đồ tuyến
--
-- mgr_route_detail() gains each outlet's position for the route map, and
-- mgr_route_reorder() sets the call order the rep's day follows (route_customer
-- .visit_order, which /route and every report already sort by). Reordering is
-- not written to the route history: it is the planner's arrangement, not a
-- change to who is called on when, and one move would add a line per outlet.
-- =============================================================================

CREATE OR REPLACE FUNCTION public.mgr_route_detail(p_route_id uuid)
 RETURNS jsonb
 LANGUAGE plpgsql
 STABLE SECURITY DEFINER
 SET search_path TO 'public', 'pg_temp'
AS $function$
declare
    v_route sales_route := team_route(p_route_id);
begin
    return jsonb_build_object(
        'id', v_route.id,
        'code', v_route.code,
        'name', v_route.name,
        'salesperson', staff_brief(v_route.salesperson_id),
        'customers', coalesce((
            select jsonb_agg(jsonb_build_object(
                'customer_id', c.id,
                'code', c.code,
                'name', c.name,
                'address', c.address,
                'lat', c.lat,
                'lng', c.lng,
                'visit_order', rc.visit_order,
                'visit_weekdays', rc.visit_weekdays,
                'visit_weeks', rc.visit_weeks,
                'last_visit', (select max(v.visit_date) from visit v
                                where v.customer_id = c.id and v.salesperson_id = v_route.salesperson_id),
                'last_order', (select max(o.order_date) from sales_order o
                                where o.customer_id = c.id and o.status <> 'cancelled')
            ) order by rc.visit_order, c.code)
            from route_customer rc
            join customer c on c.id = rc.customer_id
            where rc.sales_route_id = v_route.id and rc.is_active
        ), '[]'::jsonb),
        'history', coalesce((
            select jsonb_agg(hist.h order by hist.at desc) from (
                select x.created_at as at, jsonb_build_object(
                    'at', x.created_at, 'actor', a.full_name,
                    'text', case x.action
                        when 'added' then 'Thêm ' || c.code || ' – ' || c.name
                        when 'removed' then 'Bỏ ' || c.code || ' – ' || c.name
                        else 'Đổi lịch ' || c.code || ' – ' || c.name end,
                    'weekdays', x.visit_weekdays, 'weeks', x.visit_weeks) as h
                from route_customer_history x
                join customer c on c.id = x.customer_id
                left join salesperson a on a.id = x.actor_id
                where x.sales_route_id = v_route.id
                union all
                select y.created_at, jsonb_build_object(
                    'at', y.created_at, 'actor', a.full_name,
                    'text', 'Giao tuyến: ' || coalesce(f.full_name, '—') || ' → ' || t.full_name
                        || coalesce(' (' || y.note || ')', ''),
                    'weekdays', null, 'weeks', null)
                from sales_route_assignment_history y
                left join salesperson f on f.id = y.from_salesperson_id
                join salesperson t on t.id = y.to_salesperson_id
                left join salesperson a on a.id = y.actor_id
                where y.sales_route_id = v_route.id
                order by 1 desc
                limit 50
            ) hist
        ), '[]'::jsonb)
    );
end;
$function$
;

/** p_customer_ids: the route's outlets in the order they are to be called on. */
create or replace function mgr_route_reorder(p_route_id uuid, p_customer_ids uuid[])
returns integer
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
    v_route sales_route := team_route(p_route_id);
    v_active integer;
    v_given  integer := (select count(distinct x) from unnest(p_customer_ids) x);
    v_n      integer;
begin
    select count(*) into v_active from route_customer where sales_route_id = v_route.id and is_active;
    -- The whole route, each outlet once: a partial list would leave the rest
    -- with orders that collide with the new ones.
    if v_given <> cardinality(p_customer_ids) or v_given <> v_active
       or exists (select 1 from unnest(p_customer_ids) x
                  where not exists (select 1 from route_customer rc
                                    where rc.sales_route_id = v_route.id and rc.customer_id = x and rc.is_active)) then
        raise exception 'danh sách điểm bán không khớp với tuyến';
    end if;

    update route_customer rc
    set visit_order = o.ord
    from unnest(p_customer_ids) with ordinality as o(customer_id, ord)
    where rc.sales_route_id = v_route.id and rc.customer_id = o.customer_id
      and rc.visit_order is distinct from o.ord::integer;
    get diagnostics v_n = row_count;
    return v_n;
end;
$$;

revoke execute on function mgr_route_reorder(uuid, uuid[]) from public, anon;
grant execute on function mgr_route_reorder(uuid, uuid[]) to authenticated;
