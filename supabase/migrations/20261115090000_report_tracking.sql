-- =============================================================================
-- Báo cáo "Theo dõi bán hàng trong ngày" (legacy API_DailySalesTracking_eSalesManager)
--
-- The supervisor's live board: for each rep, where the day stands right now —
-- clocked in or not, calls planned and made, the outlet they are in, orders and
-- revenue so far — and their last sign of life: the latest of a punch, a
-- check-in or -out, an order or a GPS fix, with the battery the tracker last
-- reported. Plus the day's orders, newest first.
--
-- Same shape as the other report_*_of() cores: a list of reps, checked by
-- readable_salespeople().
-- =============================================================================

create or replace function report_tracking_of(p_sps uuid[], p_date date)
returns jsonb
language plpgsql
stable
security definer
set search_path = public, pg_temp
as $$
declare
    v_sps uuid[] := readable_salespeople(p_sps);
begin
    return jsonb_build_object(
        'date', p_date,
        'members', coalesce((
            select jsonb_agg(m order by m ->> 'code')
            from (
                select jsonb_build_object(
                    'id', s.id, 'code', s.code, 'name', s.full_name,
                    'clock_in', (select happened_at from timekeeping
                                 where salesperson_id = s.id and work_date = p_date and type = 'check_in'),
                    'clock_out', (select happened_at from timekeeping
                                  where salesperson_id = s.id and work_date = p_date and type = 'check_out'),
                    'planned', (select count(distinct rc.customer_id) from route_customer rc
                                join sales_route r on r.id = rc.sales_route_id and r.is_active
                                where r.salesperson_id = s.id and rc.is_active
                                  and on_route_day(rc.visit_weekdays, rc.visit_weeks, p_date)),
                    'visited', (select count(distinct v.customer_id) from visit v
                                where v.salesperson_id = s.id and v.visit_date = p_date and v.check_in_at is not null),
                    'in_visit', (select jsonb_build_object('code', c.code, 'name', c.name, 'since', v.check_in_at)
                                 from visit v join customer c on c.id = v.customer_id
                                 where v.salesperson_id = s.id and v.visit_date = p_date and v.status = 'in_progress'
                                 order by v.check_in_at desc limit 1),
                    'orders', (select count(*) from sales_order o
                               where o.salesperson_id = s.id and o.order_date = p_date and o.status <> 'cancelled'),
                    'revenue', (select coalesce(sum(o.total_amount), 0) from sales_order o
                                where o.salesperson_id = s.id and o.order_date = p_date and o.status <> 'cancelled'),
                    'last', (
                        select jsonb_build_object('kind', a.kind, 'at', a.at, 'detail', a.detail)
                        from (
                            select 'clock_' || t.type::text as kind, t.happened_at as at, null::text as detail
                            from timekeeping t where t.salesperson_id = s.id and t.work_date = p_date
                            union all
                            select 'check_in', v.check_in_at, c.code || ' – ' || c.name
                            from visit v join customer c on c.id = v.customer_id
                            where v.salesperson_id = s.id and v.visit_date = p_date and v.check_in_at is not null
                            union all
                            select 'check_out', v.check_out_at, c.code || ' – ' || c.name
                            from visit v join customer c on c.id = v.customer_id
                            where v.salesperson_id = s.id and v.visit_date = p_date and v.check_out_at is not null
                            union all
                            -- When the rep took it on the phone, not when it reached the server.
                            select 'order', coalesce(o.client_created_at, o.created_at), o.order_no
                            from sales_order o where o.salesperson_id = s.id and o.order_date = p_date
                            union all
                            select 'gps', l.recorded_at, null
                            from location_trace l
                            where l.salesperson_id = s.id
                              and (l.recorded_at at time zone 'Asia/Ho_Chi_Minh')::date = p_date
                        ) a
                        order by a.at desc limit 1
                    ),
                    'battery_pct', (select l.battery_pct from location_trace l
                                    where l.salesperson_id = s.id
                                      and (l.recorded_at at time zone 'Asia/Ho_Chi_Minh')::date = p_date
                                    order by l.recorded_at desc limit 1),
                    'position', (select jsonb_build_object('lat', l.lat, 'lng', l.lng, 'at', l.recorded_at)
                                 from location_trace l
                                 where l.salesperson_id = s.id
                                   and (l.recorded_at at time zone 'Asia/Ho_Chi_Minh')::date = p_date
                                 order by l.recorded_at desc limit 1)
                ) as m
                from salesperson s where s.id = any (v_sps)
            ) x
        ), '[]'::jsonb),
        'orders', coalesce((
            select jsonb_agg(jsonb_build_object(
                'order_no', o.order_no, 'created_at', coalesce(o.client_created_at, o.created_at), 'total_amount', o.total_amount,
                'salesperson', jsonb_build_object('id', s.id, 'code', s.code, 'name', s.full_name),
                'customer', c.code || ' – ' || c.name, 'status', o.status
            ) order by coalesce(o.client_created_at, o.created_at) desc)
            from sales_order o
            join salesperson s on s.id = o.salesperson_id
            join customer c on c.id = o.customer_id
            where o.salesperson_id = any (v_sps) and o.order_date = p_date
        ), '[]'::jsonb)
    );
end;
$$;

revoke execute on function report_tracking_of(uuid[], date) from public, anon;
grant execute on function report_tracking_of(uuid[], date) to authenticated;
