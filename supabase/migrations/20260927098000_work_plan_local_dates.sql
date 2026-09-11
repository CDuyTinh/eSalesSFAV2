-- =============================================================================
-- A plan's day is the rep's day, not the server's
--
-- work_plans_for and work_plan_calendar both compared `from_at::date`, which
-- casts a timestamptz using the *database's* timezone. The database runs in UTC
-- and the rep does not: a plan running 08:00 to 17:00 in Vietnam is stored as
-- 01:00 to 10:00 UTC, and one running 20:00 to 22:00 is stored on the following
-- UTC day. So an evening plan landed on tomorrow's calendar cell and dropped out
-- of a window that should have held it.
--
-- The fix is to cast at the zone the rep actually works in. Hard-coded rather
-- than read from a setting, because this app is Vietnam-only by design -- the
-- same reason the translation table is vestigial. If that ever stops being true
-- the constant is the one place to change.
-- =============================================================================

create or replace function work_plans_for(
    p_from_date    date default (current_date - 30),
    p_to_date      date default (current_date + 30),
    p_statuses     work_plan_status[] default null,
    p_category_ids uuid[] default null,
    p_assigned_to_me boolean default null,
    p_limit        integer default 50,
    p_offset       integer default 0
)
returns jsonb
language sql
stable
security invoker
set search_path = public, pg_temp
as $$
    with me as (
        select id from salesperson where id = current_salesperson_id()
    ),
    matched as (
        select
            p.*,
            c.code as category_code,
            c.name as category_name,
            c.field_training,
            c.object_kind,
            p.author_id is distinct from p.salesperson_id as assigned_to_me
        from work_plan p
        join work_with_category c on c.id = p.category_id
        cross join me
        where p.salesperson_id = me.id
          -- Overlapping the window, not starting in it: a plan that began
          -- yesterday and runs to next week belongs on this week's list.
          and (p.from_at at time zone 'Asia/Ho_Chi_Minh')::date <= p_to_date
          and (p.to_at   at time zone 'Asia/Ho_Chi_Minh')::date >= p_from_date
          and (p_category_ids is null or p.category_id = any (p_category_ids))
    ),
    -- Counts ignore the status filter on purpose: a chip has to keep showing
    -- its own number while another chip is the one selected.
    scoped as (
        select * from matched
        where p_assigned_to_me is null or assigned_to_me = p_assigned_to_me
    ),
    filtered as (
        select * from scoped
        where p_statuses is null or status = any (p_statuses)
    ),
    page as (
        select * from filtered
        order by from_at desc, created_at desc
        limit greatest(p_limit, 1) offset greatest(p_offset, 0)
    )
    select jsonb_build_object(
        'total', (select count(*) from filtered),
        'counts', coalesce((
            select jsonb_object_agg(status, n)
            from (select status, count(*) as n from scoped group by status) s
        ), '{}'::jsonb),
        'items', coalesce((
            select jsonb_agg(jsonb_build_object(
                'id', id,
                'category_id', category_id,
                'category_code', category_code,
                'category_name', category_name,
                'field_training', field_training,
                'object_kind', object_kind,
                'content', content,
                'note', note,
                'place', place,
                'from_at', from_at,
                'to_at', to_at,
                'status', status,
                'result', result,
                'reason', reason,
                'assigned_to_me', assigned_to_me,
                'object_count', (
                    select count(*) from work_plan_object o where o.plan_id = page.id
                )
            ) order by from_at desc, created_at desc)
            from page
        ), '[]'::jsonb)
    );
$$;

-- -----------------------------------------------------------------------------

create or replace function work_plan_calendar(
    p_from_date date default date_trunc('month', current_date)::date,
    p_to_date   date default (date_trunc('month', current_date) + interval '1 month - 1 day')::date
)
returns table (
    day       date,
    status    work_plan_status,
    plan_count integer
)
language sql
stable
security invoker
set search_path = public, pg_temp
as $$
    with priority(status, rank) as (
        values ('draft'::work_plan_status, 1),
               ('submitted', 2),
               ('approved', 3),
               ('completed', 4),
               ('rejected', 5),
               ('cancelled', 6)
    ),
    spread as (
        select
            d::date as day,
            p.status,
            pr.rank
        from work_plan p
        join priority pr on pr.status = p.status
        -- Clamped to the requested window so a plan running over a year does
        -- not generate a year of rows to throw away.
        cross join lateral generate_series(
            greatest((p.from_at at time zone 'Asia/Ho_Chi_Minh')::date, p_from_date),
            least((p.to_at at time zone 'Asia/Ho_Chi_Minh')::date, p_to_date),
            interval '1 day'
        ) as d
        where p.salesperson_id = current_salesperson_id()
          and (p.from_at at time zone 'Asia/Ho_Chi_Minh')::date <= p_to_date
          and (p.to_at   at time zone 'Asia/Ho_Chi_Minh')::date >= p_from_date
    )
    select
        s.day,
        (array_agg(s.status order by s.rank))[1] as status,
        count(*)::integer as plan_count
    from spread s
    group by s.day
    order by s.day;
$$;
