-- =============================================================================
-- Đi tuyến cùng nhân viên (work-with)
--
-- The supervisor rides a rep's route, checks in at the same shops, and scores
-- what they find. Legacy: the manager app's task sheet (STOCK_OUTLET, PROGRAM,
-- TAKE_ORDER, DISPLAY_REMARK, …) over McpController.CustomerMcpWorkWith /
-- API_GetCustomerMCPWorkWith, API_CheckOutValidateTimeRequired_WorkWith, and
-- PPC_DisplayRemark / PPC_WorkWithTrackingDetail.
--
-- The supervisor is a salesperson row, so their call on a shop is an ordinary
-- `visit` — their own, with `work_with_salesperson_id` naming the rep they rode
-- with. That one column is what lets everything the SFA visit already supports
-- (display_audit, visit_step_result, photos filed under the visitor's own id)
-- work for the supervisor without a parallel set of tables. Scoring a display
-- goes through SFA's own submit_display_audit, because the visit is the
-- caller's; what the supervisor scored sits beside what the rep scored, on
-- separate visits, and the screen puts the two side by side.
--
-- Visits are written here rather than through SFA's /submit-visit, which reads
-- "the" salesperson row as the first one visible — wrong for a supervisor, who
-- can see the team's rows too.
--
-- Not counted as the rep's work anywhere: dashboards and reports filter by the
-- rep's salesperson_id, and these visits carry the supervisor's.
-- =============================================================================

alter table visit
    add column work_with_salesperson_id uuid references salesperson (id);

comment on column visit.work_with_salesperson_id is
    'Set on a supervisor''s visit: the rep whose route they were riding.';

create index visit_work_with_idx on visit (work_with_salesperson_id, visit_date)
    where work_with_salesperson_id is not null;

-- -----------------------------------------------------------------------------
-- Great-circle distance in metres, for the check-in's "how far from the shop".
-- -----------------------------------------------------------------------------

create or replace function distance_m(lat1 double precision, lng1 double precision,
                                      lat2 double precision, lng2 double precision)
returns double precision
language sql
immutable
as $$
    select 2 * 6371000 * asin(sqrt(
        power(sin(radians(lat2 - lat1) / 2), 2)
        + cos(radians(lat1)) * cos(radians(lat2)) * power(sin(radians(lng2 - lng1) / 2), 2)
    ));
$$;

-- =============================================================================
-- The rep's route for the day, with both people's progress on it
-- =============================================================================

create function mgr_work_with_route(p_rep uuid, p_date date)
returns jsonb
language plpgsql
stable
security definer
set search_path = public, pg_temp
as $$
declare
    v_me      uuid := current_salesperson_id();
    v_weekday smallint := extract(isodow from p_date)::smallint;
begin
    if not is_team_member(p_rep) then
        raise exception 'nhân viên không thuộc nhóm của bạn';
    end if;

    return (
        with planned as (
            select distinct on (rc.customer_id) rc.customer_id, rc.visit_order
            from route_customer rc
            join sales_route r on r.id = rc.sales_route_id
            where r.salesperson_id = p_rep and r.is_active and rc.is_active
              and v_weekday = any (rc.visit_weekdays)
            order by rc.customer_id, rc.visit_order
        ),
        -- A shop the rep called on off-route today is still on today's ride.
        stops as (
            select customer_id, visit_order, true as planned from planned
            union
            select v.customer_id, 999, false
            from visit v
            where v.salesperson_id = p_rep and v.visit_date = p_date
              and v.customer_id not in (select customer_id from planned)
        )
        select jsonb_build_object(
            'date', p_date,
            'rep', staff_brief(p_rep),
            -- The supervisor's open call, wherever it is: one at a time.
            'open_visit_id', (select v.id from visit v
                               where v.salesperson_id = v_me and v.visit_date = p_date
                                 and v.status = 'in_progress' limit 1),
            'stops', coalesce(jsonb_agg(jsonb_build_object(
                'customer_id',  c.id,
                'code',         c.code,
                'name',         c.name,
                'address',      c.address,
                'lat',          c.lat,
                'lng',          c.lng,
                'planned',      s.planned,
                'rep_status',   (select v.status from visit v
                                  where v.salesperson_id = p_rep and v.customer_id = c.id
                                    and v.visit_date = p_date
                                  order by v.check_in_at desc nulls last limit 1),
                'rep_check_in', (select min(v.check_in_at) from visit v
                                  where v.salesperson_id = p_rep and v.customer_id = c.id
                                    and v.visit_date = p_date),
                'rep_order_amount', (select coalesce(sum(o.total_amount), 0) from sales_order o
                                  where o.salesperson_id = p_rep and o.customer_id = c.id
                                    and o.order_date = p_date and o.status <> 'cancelled'),
                'my_visit_id',  (select v.id from visit v
                                  where v.salesperson_id = v_me and v.customer_id = c.id
                                    and v.visit_date = p_date
                                  order by v.check_in_at desc nulls last limit 1),
                'my_status',    (select v.status from visit v
                                  where v.salesperson_id = v_me and v.customer_id = c.id
                                    and v.visit_date = p_date
                                  order by v.check_in_at desc nulls last limit 1)
            ) order by s.visit_order, c.code), '[]'::jsonb)
        )
        from stops s
        join customer c on c.id = s.customer_id
    );
end;
$$;

-- =============================================================================
-- Check in / check out
-- =============================================================================

create function mgr_work_with_check_in(payload jsonb)
returns uuid
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
    v_me       uuid := current_salesperson_id();
    v_rep      uuid := (payload ->> 'rep_id')::uuid;
    v_customer customer;
    v_lat      double precision := (payload ->> 'lat')::double precision;
    v_lng      double precision := (payload ->> 'lng')::double precision;
    v_date     date := coalesce((payload ->> 'visit_date')::date,
                                (now() at time zone 'Asia/Ho_Chi_Minh')::date);
    v_open     text;
    v_id       uuid := gen_random_uuid();
begin
    if v_me is null then
        raise exception 'no salesperson is linked to this account';
    end if;
    if v_rep is null or not is_team_member(v_rep) then
        raise exception 'nhân viên không thuộc nhóm của bạn';
    end if;

    select * into v_customer from customer where id = (payload ->> 'customer_id')::uuid;
    if v_customer.id is null then
        raise exception 'không tìm thấy khách hàng';
    end if;

    -- The ride is the rep's route: their outlets, or one they called on today.
    if not exists (
        select 1 from route_customer rc
        join sales_route r on r.id = rc.sales_route_id
        where r.salesperson_id = v_rep and r.is_active and rc.is_active
          and rc.customer_id = v_customer.id
    ) and not exists (
        select 1 from visit v
        where v.salesperson_id = v_rep and v.customer_id = v_customer.id and v.visit_date = v_date
    ) then
        raise exception 'khách hàng không thuộc tuyến của nhân viên';
    end if;

    select c.name into v_open
    from visit v join customer c on c.id = v.customer_id
    where v.salesperson_id = v_me and v.visit_date = v_date and v.status = 'in_progress'
    limit 1;
    if v_open is not null then
        raise exception 'Đang viếng thăm %. Check-out trước đã.', v_open;
    end if;

    insert into visit (
        id, customer_id, salesperson_id, branch_id, visit_date, status,
        check_in_at, check_in_lat, check_in_lng, check_in_accuracy_m, check_in_distance_m,
        work_with_salesperson_id, note
    )
    values (
        v_id, v_customer.id, v_me, v_customer.branch_id, v_date, 'in_progress',
        now(), v_lat, v_lng, (payload ->> 'accuracy_m')::double precision,
        case when v_lat is not null and v_customer.lat is not null
             then distance_m(v_lat, v_lng, v_customer.lat, v_customer.lng) end,
        v_rep, nullif(btrim(coalesce(payload ->> 'note', '')), '')
    );

    return v_id;
end;
$$;

create function mgr_work_with_check_out(
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
begin
    select * into v_visit from visit where id = p_visit_id for update;
    if v_visit.id is null or v_visit.salesperson_id <> current_salesperson_id() then
        raise exception 'không tìm thấy lượt viếng thăm của bạn';
    end if;
    if v_visit.status <> 'in_progress' then
        raise exception 'lượt viếng thăm này đã kết thúc';
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

-- =============================================================================
-- One call in full: the shop, what the rep did there today, and the display
-- programmes to score, with the rep's own score beside each.
-- =============================================================================

create function mgr_work_with_visit(p_visit_id uuid)
returns jsonb
language plpgsql
stable
security definer
set search_path = public, pg_temp
as $$
declare
    v_visit visit;
begin
    select * into v_visit from visit where id = p_visit_id;
    if v_visit.id is null or v_visit.salesperson_id <> current_salesperson_id() then
        raise exception 'không tìm thấy lượt viếng thăm của bạn';
    end if;

    return (
        select jsonb_build_object(
            'visit_id',     v_visit.id,
            'status',       v_visit.status,
            'check_in_at',  v_visit.check_in_at,
            'check_out_at', v_visit.check_out_at,
            'distance_m',   round(v_visit.check_in_distance_m),
            'note',         v_visit.note,
            'rep',          staff_brief(v_visit.work_with_salesperson_id),
            'customer', jsonb_build_object(
                'id', c.id, 'code', c.code, 'name', c.name, 'address', c.address,
                'phone', c.phone, 'lat', c.lat, 'lng', c.lng),
            'rep_today', jsonb_build_object(
                'status',       (select v.status from visit v
                                  where v.salesperson_id = v_visit.work_with_salesperson_id
                                    and v.customer_id = c.id and v.visit_date = v_visit.visit_date
                                  order by v.check_in_at desc nulls last limit 1),
                'check_in_at',  (select min(v.check_in_at) from visit v
                                  where v.salesperson_id = v_visit.work_with_salesperson_id
                                    and v.customer_id = c.id and v.visit_date = v_visit.visit_date),
                'order_amount', (select coalesce(sum(o.total_amount), 0) from sales_order o
                                  where o.salesperson_id = v_visit.work_with_salesperson_id
                                    and o.customer_id = c.id and o.order_date = v_visit.visit_date
                                    and o.status <> 'cancelled')
            ),
            'programs', coalesce((
                select jsonb_agg(jsonb_build_object(
                    'program_id',     p.program_id,
                    'program_code',   p.program_code,
                    'program_name',   p.program_name,
                    'level_id',       p.level_id,
                    'level_name',     p.level_name,
                    'required_faces', p.required_faces,
                    'registered',     p.registered,
                    'my_audit_id',    p.audit_id,
                    'my_faces',       p.counted_faces,
                    'my_achieved',    p.achieved,
                    'my_photos',      p.photo_count,
                    -- The rep's most recent score for this programme at this shop.
                    'rep_audit', (select jsonb_build_object(
                                      'audit_date', a.audit_date,
                                      'counted_faces', a.counted_faces,
                                      'achieved', a.achieved)
                                  from display_audit a
                                  where a.salesperson_id = v_visit.work_with_salesperson_id
                                    and a.customer_id = c.id and a.program_id = p.program_id
                                  order by a.audit_date desc, a.created_at desc limit 1)
                ) order by p.program_code)
                from display_programs_for(c.id, v_visit.id) p
            ), '[]'::jsonb)
        )
        from customer c
        where c.id = v_visit.customer_id
    );
end;
$$;

-- -----------------------------------------------------------------------------
-- Grants (default privileges give anon EXECUTE on new functions; take it back).
-- -----------------------------------------------------------------------------

revoke execute on function mgr_work_with_route(uuid, date) from public, anon;
revoke execute on function mgr_work_with_check_in(jsonb) from public, anon;
revoke execute on function mgr_work_with_check_out(uuid, text, double precision, double precision) from public, anon;
revoke execute on function mgr_work_with_visit(uuid) from public, anon;

grant execute on function mgr_work_with_route(uuid, date) to authenticated;
grant execute on function mgr_work_with_check_in(jsonb) to authenticated;
grant execute on function mgr_work_with_check_out(uuid, text, double precision, double precision) to authenticated;
grant execute on function mgr_work_with_visit(uuid) to authenticated;
