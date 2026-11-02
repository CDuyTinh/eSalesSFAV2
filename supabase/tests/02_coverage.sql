-- Coverage ("Viếng thăm x/y", "Độ phủ tuyến") counts route stops reached,
-- never more than the route: an off-route call is a visit, not coverage.

-- Invariants over October and the start of November, for every rep and the
-- AM's whole tree: what was reached never exceeds what was planned.
do $$
declare
    d date;
    v jsonb;
    m jsonb;
begin
    for d in select generate_series(date '2026-10-01', date '2026-11-02', interval '1 day')::date loop
        perform pg_temp.act_as('nvbh01');
        v := dashboard_overview(d) -> 'today';
        perform pg_temp.check((v ->> 'visit_done')::int <= (v ->> 'visit_planned')::int,
            format('SFA overview %s: visited %s of %s planned', d, v ->> 'visit_done', v ->> 'visit_planned'));

        v := report_activities_of(array[pg_temp.sp('nvbh01')], d) -> 'summary';
        perform pg_temp.check((v ->> 'visited')::int <= (v ->> 'planned')::int,
            format('activities report %s: visited %s of %s planned', d, v ->> 'visited', v ->> 'planned'));

        perform pg_temp.act_as('amkv01');
        v := mgr_dashboard(d);
        perform pg_temp.check((v -> 'today' ->> 'visit_done')::int <= (v -> 'today' ->> 'visit_planned')::int,
            format('manager overview %s: today over plan', d));
        perform pg_temp.check((v -> 'month' ->> 'visit_done')::int <= (v -> 'month' ->> 'visit_planned')::int,
            format('manager overview %s: month over plan', d));
        for m in select jsonb_array_elements(v -> 'members') loop
            perform pg_temp.check((m ->> 'today_visits')::int <= (m ->> 'today_planned')::int,
                format('manager overview %s: %s over plan', d, m ->> 'code'));
        end loop;
    end loop;
end;
$$;

-- An off-route call, added here and rolled back with the rest: the visit
-- report counts it as off-route, and neither overview's coverage moves.
do $$
declare
    v_day      date := date '2026-11-01';
    v_off      uuid := (select c.id from customer c
                         where not exists (select 1 from route_customer rc
                                           join sales_route r on r.id = rc.sales_route_id
                                           where rc.customer_id = c.id and r.salesperson_id = pg_temp.sp('nvbh01'))
                           and not exists (select 1 from visit v
                                           where v.customer_id = c.id and v.salesperson_id = pg_temp.sp('nvbh01')
                                             and v.visit_date = date '2026-11-01')
                         limit 1);
    v_done     int;
    v_mgr_done int;
    v_unplan   int;
begin
    perform pg_temp.check(v_off is not null, 'an off-route customer exists for nvbh01');

    perform pg_temp.act_as('nvbh01');
    v_done := (dashboard_overview(v_day) -> 'today' ->> 'visit_done')::int;
    v_unplan := (report_activities_of(array[pg_temp.sp('nvbh01')], v_day) -> 'summary' ->> 'unplanned')::int;
    perform pg_temp.act_as('amkv01');
    v_mgr_done := (mgr_dashboard(v_day) -> 'today' ->> 'visit_done')::int;

    perform pg_temp.act_as_admin();
    insert into visit (customer_id, salesperson_id, branch_id, visit_date, status, check_in_at, check_out_at)
    select v_off, pg_temp.sp('nvbh01'), s.branch_id, v_day, 'completed',
           v_day + time '15:00', v_day + time '15:10'
    from salesperson s where s.id = pg_temp.sp('nvbh01');

    perform pg_temp.act_as('nvbh01');
    perform pg_temp.check((dashboard_overview(v_day) -> 'today' ->> 'visit_done')::int = v_done,
        'an off-route call leaves the SFA overview''s coverage alone');
    perform pg_temp.check(
        (report_activities_of(array[pg_temp.sp('nvbh01')], v_day) -> 'summary' ->> 'unplanned')::int = v_unplan + 1,
        'the activities report counts it as off-route');
    perform pg_temp.act_as('amkv01');
    perform pg_temp.check((mgr_dashboard(v_day) -> 'today' ->> 'visit_done')::int = v_mgr_done,
        'an off-route call leaves the manager overview''s coverage alone');
end;
$$;

do $$ begin raise exception 'ALL PASSED'; end $$;
