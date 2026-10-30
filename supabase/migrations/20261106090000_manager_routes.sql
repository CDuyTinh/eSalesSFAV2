-- =============================================================================
-- Lập tuyến (MCP) và phân tuyến
--
-- Two legacy screens over the same two tables:
--   * MCP — which outlets a route calls on, on which weekdays and which weeks of
--     the 4-week cycle (McpController: CustomerMcp, Customer, GenerateMcp,
--     DeleteMcp, McpHistory; procs API_GetCustomerMCP*, OM28500_*).
--   * Route assignment — which rep runs a route (RouteAssignmentController:
--     API_GetRouteAssignment*, API_UpdateRouteAssignment).
-- Here: sales_route.salesperson_id and route_customer, which SFA already reads
-- for the rep's day, so a change made by the supervisor is the rep's route the
-- next time they open the app.
--
-- A supervisor may touch a route that one of their reps runs, and may hand it
-- only to another rep in the team. Every change is written to a history table,
-- as the legacy kept OM_SalesRouteMasterHistory / OM_SalespersonRouteHistory —
-- routes move money, and "who took KH005 off Nam's Thursday" is a question
-- somebody will ask.
--
-- Removing an outlet deactivates the line rather than deleting it: past visits
-- and the history still point at it, and putting it back is one update.
-- =============================================================================

create table route_customer_history (
    id             uuid primary key default gen_random_uuid(),
    sales_route_id uuid not null references sales_route (id) on delete cascade,
    customer_id    uuid not null references customer (id) on delete cascade,
    action         text not null check (action in ('added', 'changed', 'removed')),
    visit_weekdays smallint[],
    visit_weeks    smallint[],
    actor_id       uuid references salesperson (id),
    created_at     timestamptz not null default now()
);

create index on route_customer_history (sales_route_id, created_at desc);

create table sales_route_assignment_history (
    id                  uuid primary key default gen_random_uuid(),
    sales_route_id      uuid not null references sales_route (id) on delete cascade,
    from_salesperson_id uuid references salesperson (id),
    to_salesperson_id   uuid not null references salesperson (id),
    note                text,
    actor_id            uuid references salesperson (id),
    created_at          timestamptz not null default now()
);

create index on sales_route_assignment_history (sales_route_id, created_at desc);

-- Written only by the functions below; nobody reads them directly.
alter table route_customer_history enable row level security;
alter table sales_route_assignment_history enable row level security;

-- -----------------------------------------------------------------------------
-- Guard: the route is run by someone in the caller's team.
-- -----------------------------------------------------------------------------

create function team_route(p_route_id uuid)
returns sales_route
language plpgsql
stable
security definer
set search_path = public, pg_temp
as $$
declare
    v_route sales_route;
begin
    select * into v_route from sales_route where id = p_route_id;
    if v_route.id is null or not is_team_member(v_route.salesperson_id) then
        raise exception 'tuyến không thuộc nhân viên trong nhóm của bạn';
    end if;
    return v_route;
end;
$$;

-- =============================================================================
-- Reading
-- =============================================================================

/** Every route the team runs, with how many outlets each calls on per weekday. */
create function mgr_routes()
returns jsonb
language sql
stable
security definer
set search_path = public, pg_temp
as $$
    select coalesce(jsonb_agg(jsonb_build_object(
        'id', r.id,
        'code', r.code,
        'name', r.name,
        'is_active', r.is_active,
        'salesperson', staff_brief(r.salesperson_id),
        'customer_count', (select count(*) from route_customer rc
                            where rc.sales_route_id = r.id and rc.is_active),
        -- Outlets per weekday, Monday first: the shape of the route's week.
        'per_weekday', (select jsonb_agg(
                            (select count(*) from route_customer rc
                              where rc.sales_route_id = r.id and rc.is_active
                                and d = any (rc.visit_weekdays)) order by d)
                        from generate_series(1, 7) d)
    ) order by r.code), '[]'::jsonb)
    from sales_route r
    where r.salesperson_id in (select team_salesperson_ids());
$$;

/** One route's outlets with their schedule and last activity. */
create function mgr_route_detail(p_route_id uuid)
returns jsonb
language plpgsql
stable
security definer
set search_path = public, pg_temp
as $$
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
$$;

/** Outlets that could join the route: approved, active, in its branch, not on it. */
create function mgr_route_candidates(p_route_id uuid, p_query text default null)
returns jsonb
language plpgsql
stable
security definer
set search_path = public, pg_temp
as $$
declare
    v_route sales_route := team_route(p_route_id);
begin
    return coalesce((
        select jsonb_agg(jsonb_build_object(
            'customer_id', c.id, 'code', c.code, 'name', c.name, 'address', c.address,
            -- Other routes it is already on, so a supervisor sees a double booking.
            'on_routes', (select string_agg(r2.code, ', ' order by r2.code)
                           from route_customer rc2 join sales_route r2 on r2.id = rc2.sales_route_id
                          where rc2.customer_id = c.id and rc2.is_active and r2.is_active)
        ) order by c.code)
        from customer c
        where c.branch_id = v_route.branch_id
          and c.is_active
          and c.approval_status = 'approved'
          and not exists (select 1 from route_customer rc
                           where rc.sales_route_id = v_route.id and rc.customer_id = c.id and rc.is_active)
          and (p_query is null or btrim(p_query) = ''
               or c.name ilike '%' || btrim(p_query) || '%'
               or c.code ilike '%' || btrim(p_query) || '%')
        limit 100
    ), '[]'::jsonb);
end;
$$;

-- =============================================================================
-- Writing
-- =============================================================================

/**
 * Puts an outlet on the route, or changes its days. An outlet needs at least
 * one weekday: on a route with no day it is on nobody's round, which is a
 * removal by another name and should be said as one.
 */
create function mgr_route_set_customer(
    p_route_id    uuid,
    p_customer_id uuid,
    p_weekdays    smallint[],
    p_weeks       smallint[] default '{}'
)
returns jsonb
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
    v_route  sales_route := team_route(p_route_id);
    v_line   route_customer;
    v_action text;
    v_days   smallint[] := (select coalesce(array_agg(distinct d order by d), '{}') from unnest(p_weekdays) d);
    v_weeks  smallint[] := (select coalesce(array_agg(distinct w order by w), '{}') from unnest(coalesce(p_weeks, '{}')) w);
begin
    if cardinality(v_days) = 0 then
        raise exception 'chọn ít nhất một ngày ghé';
    end if;
    if not v_days <@ array[1,2,3,4,5,6,7]::smallint[] or not v_weeks <@ array[1,2,3,4]::smallint[] then
        raise exception 'lịch ghé không hợp lệ';
    end if;
    if not exists (select 1 from customer c where c.id = p_customer_id
                    and c.branch_id = v_route.branch_id and c.is_active
                    and c.approval_status = 'approved') then
        raise exception 'khách hàng không thuộc chi nhánh của tuyến hoặc chưa được duyệt';
    end if;

    select * into v_line from route_customer
     where sales_route_id = v_route.id and customer_id = p_customer_id for update;

    if v_line.id is null then
        insert into route_customer (sales_route_id, customer_id, visit_order, visit_weekdays, visit_weeks)
        values (v_route.id, p_customer_id,
                (select coalesce(max(visit_order), 0) + 1 from route_customer where sales_route_id = v_route.id),
                v_days, v_weeks);
        v_action := 'added';
    elsif not v_line.is_active then
        update route_customer set is_active = true, visit_weekdays = v_days, visit_weeks = v_weeks,
               visit_order = (select coalesce(max(visit_order), 0) + 1 from route_customer
                               where sales_route_id = v_route.id and is_active)
         where id = v_line.id;
        v_action := 'added';
    else
        if v_line.visit_weekdays = v_days and v_line.visit_weeks = v_weeks then
            return jsonb_build_object('changed', false);
        end if;
        update route_customer set visit_weekdays = v_days, visit_weeks = v_weeks where id = v_line.id;
        v_action := 'changed';
    end if;

    insert into route_customer_history (sales_route_id, customer_id, action, visit_weekdays, visit_weeks, actor_id)
    values (v_route.id, p_customer_id, v_action, v_days, v_weeks, current_salesperson_id());

    return jsonb_build_object('changed', true, 'action', v_action);
end;
$$;

create function mgr_route_remove_customer(p_route_id uuid, p_customer_id uuid)
returns jsonb
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
    v_route sales_route := team_route(p_route_id);
    v_line  route_customer;
begin
    select * into v_line from route_customer
     where sales_route_id = v_route.id and customer_id = p_customer_id and is_active for update;
    if v_line.id is null then
        raise exception 'khách hàng không có trên tuyến';
    end if;

    update route_customer set is_active = false where id = v_line.id;

    insert into route_customer_history (sales_route_id, customer_id, action, visit_weekdays, visit_weeks, actor_id)
    values (v_route.id, p_customer_id, 'removed', v_line.visit_weekdays, v_line.visit_weeks, current_salesperson_id());

    return jsonb_build_object('removed', true);
end;
$$;

/**
 * Hands the route to another rep in the team (API_UpdateRouteAssignment). The
 * outlets move with it: the route is the unit, as it was over there.
 */
create function mgr_route_assign(p_route_id uuid, p_salesperson_id uuid, p_note text default null)
returns jsonb
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
    v_route sales_route := team_route(p_route_id);
begin
    if p_salesperson_id is null or not is_team_member(p_salesperson_id) then
        raise exception 'chỉ giao tuyến cho nhân viên trong nhóm của bạn';
    end if;
    if p_salesperson_id = v_route.salesperson_id then
        return jsonb_build_object('changed', false);
    end if;

    update sales_route set salesperson_id = p_salesperson_id where id = v_route.id;

    insert into sales_route_assignment_history
        (sales_route_id, from_salesperson_id, to_salesperson_id, note, actor_id)
    values (v_route.id, v_route.salesperson_id, p_salesperson_id,
            nullif(btrim(coalesce(p_note, '')), ''), current_salesperson_id());

    return jsonb_build_object('changed', true);
end;
$$;

-- -----------------------------------------------------------------------------
-- Grants (anon gets EXECUTE on new functions by default here; take it back).
-- team_route is internal.
-- -----------------------------------------------------------------------------

revoke execute on function team_route(uuid) from public, anon, authenticated;
revoke execute on function mgr_routes() from public, anon;
revoke execute on function mgr_route_detail(uuid) from public, anon;
revoke execute on function mgr_route_candidates(uuid, text) from public, anon;
revoke execute on function mgr_route_set_customer(uuid, uuid, smallint[], smallint[]) from public, anon;
revoke execute on function mgr_route_remove_customer(uuid, uuid) from public, anon;
revoke execute on function mgr_route_assign(uuid, uuid, text) from public, anon;

grant execute on function mgr_routes() to authenticated;
grant execute on function mgr_route_detail(uuid) to authenticated;
grant execute on function mgr_route_candidates(uuid, text) to authenticated;
grant execute on function mgr_route_set_customer(uuid, uuid, smallint[], smallint[]) to authenticated;
grant execute on function mgr_route_remove_customer(uuid, uuid) to authenticated;
grant execute on function mgr_route_assign(uuid, uuid, text) to authenticated;
