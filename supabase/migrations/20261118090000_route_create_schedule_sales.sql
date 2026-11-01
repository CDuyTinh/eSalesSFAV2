-- =============================================================================
-- Lập tuyến, the rest of it: a new route, a hand-over that starts on a given
-- day, and what the route has sold (legacy McpController create,
-- RouteAssignment effective date, FetchRouteAssignmentSaleHistoryList).
--
-- A hand-over dated in the future is written down and left pending; a nightly
-- job applies whatever has come due, so the rep's app — which reads
-- sales_route.salesperson_id as it always has — changes hands on the morning
-- of that day and not before. One dated today or earlier applies at once, as
-- every hand-over used to.
-- =============================================================================

create extension if not exists pg_cron;

alter table sales_route_assignment_history
    add column effective_date date,
    add column applied_at     timestamptz;

-- Everything recorded so far took effect when it was written.
update sales_route_assignment_history
set effective_date = (created_at at time zone 'Asia/Ho_Chi_Minh')::date,
    applied_at     = created_at
where applied_at is null;

create index on sales_route_assignment_history (effective_date) where applied_at is null;

-- -----------------------------------------------------------------------------
-- Hand-over, now or from a date
-- -----------------------------------------------------------------------------

drop function mgr_route_assign(uuid, uuid, text);

/**
 * Hands the route to another rep in the team (API_UpdateRouteAssignment). The
 * outlets move with it. With p_effective_date after today the change waits
 * for that morning; a later call replaces a hand-over still waiting.
 */
create function mgr_route_assign(
    p_route_id       uuid,
    p_salesperson_id uuid,
    p_note           text default null,
    p_effective_date date default null
)
returns jsonb
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
    v_route sales_route := team_route(p_route_id);
    v_today date := (now() at time zone 'Asia/Ho_Chi_Minh')::date;
    v_from  date := coalesce(p_effective_date, v_today);
begin
    if p_salesperson_id is null or not is_team_member(p_salesperson_id)
       or (select position from salesperson where id = p_salesperson_id) <> 'sr' then
        raise exception 'chỉ giao tuyến cho nhân viên bán hàng trong nhóm của bạn';
    end if;

    -- Whatever was waiting is superseded, whichever way this one goes.
    delete from sales_route_assignment_history
    where sales_route_id = v_route.id and applied_at is null;

    if v_from > v_today then
        insert into sales_route_assignment_history
            (sales_route_id, from_salesperson_id, to_salesperson_id, note, actor_id, effective_date)
        values (v_route.id, v_route.salesperson_id, p_salesperson_id,
                nullif(btrim(coalesce(p_note, '')), ''), current_salesperson_id(), v_from);
        return jsonb_build_object('changed', false, 'scheduled', v_from);
    end if;

    if p_salesperson_id = v_route.salesperson_id then
        return jsonb_build_object('changed', false);
    end if;

    update sales_route set salesperson_id = p_salesperson_id where id = v_route.id;

    insert into sales_route_assignment_history
        (sales_route_id, from_salesperson_id, to_salesperson_id, note, actor_id, effective_date, applied_at)
    values (v_route.id, v_route.salesperson_id, p_salesperson_id,
            nullif(btrim(coalesce(p_note, '')), ''), current_salesperson_id(), v_today, now());

    return jsonb_build_object('changed', true);
end;
$$;

/** Applies every hand-over that has come due. Run nightly; safe to run again. */
create function apply_due_route_assignments()
returns integer
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
    v_today date := (now() at time zone 'Asia/Ho_Chi_Minh')::date;
    v_row   sales_route_assignment_history;
    v_count integer := 0;
begin
    for v_row in
        select * from sales_route_assignment_history
        where applied_at is null and effective_date <= v_today
        order by effective_date, created_at
        for update skip locked
    loop
        update sales_route_assignment_history h
        set applied_at = now(),
            -- Who actually held it when it changed hands, which may differ
            -- from who held it when the hand-over was written.
            from_salesperson_id = (select salesperson_id from sales_route where id = v_row.sales_route_id)
        where h.id = v_row.id;

        update sales_route set salesperson_id = v_row.to_salesperson_id where id = v_row.sales_route_id;
        v_count := v_count + 1;
    end loop;
    return v_count;
end;
$$;

-- 00:05 in Vietnam.
select cron.schedule('apply-due-route-assignments', '5 17 * * *', 'select public.apply_due_route_assignments()');

/** The hand-over waiting on a route, if any, for the route screen. */
create function mgr_route_pending(p_route_id uuid)
returns jsonb
language plpgsql
stable
security definer
set search_path = public, pg_temp
as $$
declare
    v_route sales_route := team_route(p_route_id);
begin
    return (
        select jsonb_build_object(
            'to', staff_brief(h.to_salesperson_id),
            'effective_date', h.effective_date,
            'note', h.note
        )
        from sales_route_assignment_history h
        where h.sales_route_id = v_route.id and h.applied_at is null
        order by h.created_at desc
        limit 1
    );
end;
$$;

-- -----------------------------------------------------------------------------
-- A new route
-- -----------------------------------------------------------------------------

/**
 * Opens an empty route for a rep in the team, at the rep's distributor. With no
 * code given, the next free R-number is used.
 */
create function mgr_route_create(p_salesperson_id uuid, p_name text, p_code text default null)
returns uuid
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
    v_rep    salesperson;
    v_code   text := upper(nullif(btrim(coalesce(p_code, '')), ''));
    v_name   text := nullif(btrim(coalesce(p_name, '')), '');
    v_id     uuid;
begin
    select * into v_rep from salesperson where id = p_salesperson_id;
    if v_rep.id is null or not is_team_member(v_rep.id) or v_rep.position <> 'sr' then
        raise exception 'chọn nhân viên bán hàng trong nhóm của bạn';
    end if;
    if v_name is null then
        raise exception 'vui lòng nhập tên tuyến';
    end if;

    if v_code is null then
        select 'R' || lpad((coalesce(max(nullif(regexp_replace(code, '\D', '', 'g'), '')::integer), 0) + 1)::text, 2, '0')
        into v_code
        from sales_route
        where code ~ '^R\d+$';
    elsif exists (select 1 from sales_route where code = v_code) then
        raise exception 'mã tuyến % đã có', v_code;
    end if;

    insert into sales_route (code, name, branch_id, salesperson_id, is_active)
    values (v_code, v_name, v_rep.branch_id, v_rep.id, true)
    returning id into v_id;

    insert into sales_route_assignment_history
        (sales_route_id, from_salesperson_id, to_salesperson_id, note, actor_id, effective_date, applied_at)
    values (v_id, null, v_rep.id, 'Tạo tuyến', current_salesperson_id(),
            (now() at time zone 'Asia/Ho_Chi_Minh')::date, now());

    return v_id;
end;
$$;

-- -----------------------------------------------------------------------------
-- What the route has sold
-- -----------------------------------------------------------------------------

/**
 * Month by month, what the route's current outlets bought and from whom — so a
 * route that changed hands shows each rep's months side by side.
 */
create function mgr_route_sales(p_route_id uuid, p_months integer default 6)
returns jsonb
language plpgsql
stable
security definer
set search_path = public, pg_temp
as $$
declare
    v_route sales_route := team_route(p_route_id);
    v_first date := (date_trunc('month', now() at time zone 'Asia/Ho_Chi_Minh')
                     - make_interval(months => greatest(least(p_months, 24), 1) - 1))::date;
begin
    return coalesce((
        with orders as (
            select date_trunc('month', o.order_date)::date as month, o.salesperson_id, o.total_amount
            from sales_order o
            where o.customer_id in (select rc.customer_id from route_customer rc
                                    where rc.sales_route_id = v_route.id and rc.is_active)
              and o.status <> 'cancelled'
              and o.order_date >= v_first
        ),
        months as (
            select generate_series(v_first, date_trunc('month', now() at time zone 'Asia/Ho_Chi_Minh')::date, interval '1 month')::date as month
        )
        select jsonb_agg(jsonb_build_object(
            'month', m.month,
            'revenue', coalesce((select sum(total_amount) from orders o where o.month = m.month), 0),
            'orders', (select count(*) from orders o where o.month = m.month),
            'by_salesperson', coalesce((
                select jsonb_agg(jsonb_build_object(
                    'salesperson', staff_brief(x.salesperson_id), 'revenue', x.revenue, 'orders', x.n
                ) order by x.revenue desc)
                from (select salesperson_id, sum(total_amount) as revenue, count(*) as n
                      from orders o where o.month = m.month group by salesperson_id) x
            ), '[]'::jsonb)
        ) order by m.month desc)
        from months m
    ), '[]'::jsonb);
end;
$$;

revoke execute on function mgr_route_assign(uuid, uuid, text, date) from public, anon;
revoke execute on function apply_due_route_assignments() from public, anon, authenticated;
revoke execute on function mgr_route_pending(uuid) from public, anon;
revoke execute on function mgr_route_create(uuid, text, text) from public, anon;
revoke execute on function mgr_route_sales(uuid, integer) from public, anon;
grant execute on function mgr_route_assign(uuid, uuid, text, date) to authenticated;
grant execute on function mgr_route_pending(uuid) to authenticated;
grant execute on function mgr_route_create(uuid, text, text) to authenticated;
grant execute on function mgr_route_sales(uuid, integer) to authenticated;
