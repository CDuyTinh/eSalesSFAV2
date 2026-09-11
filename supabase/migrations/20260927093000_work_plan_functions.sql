-- =============================================================================
-- Reading and driving a work plan
--
-- Five entry points, matching the five things the legacy's two controllers do
-- between them: the catalogue, the list with its per-status counts, the month
-- calendar, one plan in full, and the transitions.
-- =============================================================================

create function work_with_categories()
returns table (
    id             uuid,
    code           text,
    name           text,
    field_training boolean,
    trade_program  boolean,
    object_kind    work_plan_object_kind,
    min_sales      bigint,
    max_sales      bigint
)
language sql
stable
security invoker
set search_path = public, pg_temp
as $$
    select c.id, c.code, c.name, c.field_training, c.trade_program,
           c.object_kind, c.min_sales, c.max_sales
    from work_with_category c
    where c.is_active
    order by c.sort_order, c.name;
$$;

comment on function work_with_categories() is
    'The work-type catalogue. API_GetListTaskType / TaskManagement/Category.';

-- -----------------------------------------------------------------------------
-- The list
--
-- API_GetTaskList returns three things in one round trip: the rows, the count
-- per status, and the total. So does this -- the counts are what the filter
-- chips show, and fetching them separately is how a chip ends up claiming four
-- when the list holds three.
-- -----------------------------------------------------------------------------

create function work_plans_for(
    p_from_date    date default (current_date - 30),
    p_to_date      date default (current_date + 30),
    p_statuses     work_plan_status[] default null,
    p_category_ids uuid[] default null,
    /** Null is both; true is only what someone else planned for me. */
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
    -- Overlapping the window, not starting in it: a plan that began yesterday
    -- and runs to next week belongs on this week's list.
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
          and p.from_at::date <= p_to_date
          and p.to_at::date   >= p_from_date
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

comment on function work_plans_for(date, date, work_plan_status[], uuid[], boolean, integer, integer) is
    'The rep''s plans in a window, with the per-status counts the filter chips need. API_GetTaskList and TaskManagement Get.';

-- -----------------------------------------------------------------------------
-- The calendar
--
-- One status per day, so a month grid can be painted in a single colour per
-- cell. API_GetTaskCalender does this with a recursive CTE that expands each
-- plan across the days it spans, then ranks by priority; so does this.
--
-- The priority order is not the legacy's. Over there approved outranks
-- everything because the proc only ever sees A, R, V and C -- it has no draft
-- to rank. Here a day holding an unsent draft and an approved plan should say
-- draft, because the draft is the one still asking the rep for something. So
-- the order is "what still needs doing" first.
-- -----------------------------------------------------------------------------

create function work_plan_calendar(
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
            greatest(p.from_at::date, p_from_date),
            least(p.to_at::date, p_to_date),
            interval '1 day'
        ) as d
        where p.salesperson_id = current_salesperson_id()
          and p.from_at::date <= p_to_date
          and p.to_at::date   >= p_from_date
    )
    select
        s.day,
        (array_agg(s.status order by s.rank))[1] as status,
        count(*)::integer as plan_count
    from spread s
    group by s.day
    order by s.day;
$$;

comment on function work_plan_calendar(date, date) is
    'One status per day for the month grid, most-urgent-wins. API_GetTaskCalender.';

-- -----------------------------------------------------------------------------
-- One plan in full
-- -----------------------------------------------------------------------------

create function work_plan_detail(p_plan_id uuid)
returns jsonb
language sql
stable
security invoker
set search_path = public, pg_temp
as $$
    select jsonb_build_object(
        'id', p.id,
        'category_id', p.category_id,
        'category_code', c.code,
        'category_name', c.name,
        'field_training', c.field_training,
        'trade_program', c.trade_program,
        'object_kind', c.object_kind,
        'content', p.content,
        'note', p.note,
        'place', p.place,
        'from_at', p.from_at,
        'to_at', p.to_at,
        'status', p.status,
        'result', p.result,
        'reason', p.reason,
        'assigned_to_me', p.author_id is distinct from p.salesperson_id,
        'submitted_at', p.submitted_at,
        'approved_at', p.approved_at,
        'rejected_at', p.rejected_at,
        'completed_at', p.completed_at,
        'cancelled_at', p.cancelled_at,
        'objects', coalesce((
            select jsonb_agg(jsonb_build_object(
                'id', o.id,
                'kind', o.kind,
                'target_id', coalesce(o.customer_id, o.target_id, o.target_branch_id),
                'name', coalesce(cus.name, sp.full_name, br.name),
                'code', coalesce(cus.code, sp.code, br.code),
                'address', cus.address
            ) order by coalesce(cus.name, sp.full_name, br.name))
            from work_plan_object o
            left join customer   cus on cus.id = o.customer_id
            left join salesperson sp on sp.id  = o.target_id
            left join branch      br on br.id  = o.target_branch_id
            where o.plan_id = p.id
        ), '[]'::jsonb),
        'history', coalesce((
            select jsonb_agg(jsonb_build_object(
                'status', h.status,
                'reason', h.reason,
                'actor', a.full_name,
                'at', h.created_at
            ) order by h.created_at)
            from work_plan_history h
            left join salesperson a on a.id = h.actor_id
            where h.plan_id = p.id
        ), '[]'::jsonb),
        'photos', coalesce((
            select jsonb_agg(jsonb_build_object(
                'id', ph.id,
                'storage_path', ph.storage_path,
                'taken_at', ph.taken_at
            ) order by ph.created_at)
            from work_plan_photo ph
            where ph.plan_id = p.id
        ), '[]'::jsonb)
    )
    from work_plan p
    join work_with_category c on c.id = p.category_id
    where p.id = p_plan_id;
$$;

comment on function work_plan_detail(uuid) is
    'One plan with its objects, history and photos. API_GetTaskDetail.';

-- -----------------------------------------------------------------------------
-- Writing a draft
--
-- Create and update in one function, because the form is the same form and the
-- only difference is whether an id came with it.
-- -----------------------------------------------------------------------------

create function save_work_plan(payload jsonb)
returns uuid
language plpgsql
security invoker
set search_path = public, pg_temp
as $$
declare
    v_me         uuid := current_salesperson_id();
    v_branch     uuid;
    v_plan_id    uuid := nullif(payload ->> 'id', '')::uuid;
    v_category   uuid := (payload ->> 'category_id')::uuid;
    v_object_kind work_plan_object_kind;
    v_from       timestamptz := (payload ->> 'from_at')::timestamptz;
    v_to         timestamptz := (payload ->> 'to_at')::timestamptz;
    v_content    text := btrim(coalesce(payload ->> 'content', ''));
    v_objects    jsonb := coalesce(payload -> 'objects', '[]'::jsonb);
    v_object     jsonb;
    v_existing   work_plan_status;
begin
    if v_me is null then
        raise exception 'no salesperson for this account';
    end if;

    select branch_id into v_branch from salesperson where id = v_me;

    if v_content = '' then
        raise exception 'content is required';
    end if;

    if v_from is null or v_to is null or v_to <= v_from then
        raise exception 'the plan must end after it starts';
    end if;

    select object_kind into v_object_kind
    from work_with_category where id = v_category and is_active;

    if not found then
        raise exception 'unknown work type';
    end if;

    -- The legacy form refuses a plan with no object when the category asks for
    -- one, and it is right to: "work with a customer" naming no customer is a
    -- plan nobody can act on.
    if v_object_kind is not null and jsonb_array_length(v_objects) = 0 then
        raise exception 'this work type needs at least one %', v_object_kind;
    end if;

    if v_object_kind is null and jsonb_array_length(v_objects) > 0 then
        raise exception 'this work type takes no objects';
    end if;

    if v_plan_id is null then
        insert into work_plan (
            salesperson_id, author_id, branch_id, category_id,
            content, note, place, from_at, to_at, status
        )
        values (
            v_me, v_me, v_branch, v_category,
            v_content,
            nullif(btrim(coalesce(payload ->> 'note', '')), ''),
            nullif(btrim(coalesce(payload ->> 'place', '')), ''),
            v_from, v_to, 'draft'
        )
        returning id into v_plan_id;

        insert into work_plan_history (plan_id, status, actor_id)
        values (v_plan_id, 'draft', v_me);
    else
        -- The RLS update policy already refuses anything but the rep's own
        -- draft. It refuses it by matching no rows, though, which is silent --
        -- so the row is read first and the refusal is said out loud.
        select status into v_existing
        from work_plan
        where id = v_plan_id and salesperson_id = v_me;

        if not found then
            raise exception 'plan not found';
        end if;

        if v_existing <> 'draft' then
            raise exception 'a % plan can no longer be edited', v_existing;
        end if;

        update work_plan
        set category_id = v_category,
            content     = v_content,
            note        = nullif(btrim(coalesce(payload ->> 'note', '')), ''),
            place       = nullif(btrim(coalesce(payload ->> 'place', '')), ''),
            from_at     = v_from,
            to_at       = v_to
        where id = v_plan_id;

        delete from work_plan_object where plan_id = v_plan_id;
    end if;

    for v_object in select * from jsonb_array_elements(v_objects)
    loop
        insert into work_plan_object (
            plan_id, kind, customer_id, target_id, target_branch_id
        )
        values (
            v_plan_id,
            v_object_kind,
            case when v_object_kind = 'customer'    then (v_object ->> 'id')::uuid end,
            case when v_object_kind = 'salesperson' then (v_object ->> 'id')::uuid end,
            case when v_object_kind = 'branch'      then (v_object ->> 'id')::uuid end
        )
        -- The same customer named twice is one customer, not an error worth
        -- throwing a whole form away for.
        on conflict do nothing;
    end loop;

    return v_plan_id;
end;
$$;

comment on function save_work_plan(jsonb) is
    'Creates or updates the rep''s own draft plan and its objects. TaskManagement Create/Update.';

-- -----------------------------------------------------------------------------
-- The state machine
--
-- Security definer, because it is the only thing allowed to move a plan out of
-- 'draft' -- the rep's own update policy stops at the draft on purpose, so that
-- the transition rules live here and cannot be walked around with a plain
-- UPDATE. Every branch therefore re-checks who is asking.
--
-- Transitions, straight out of UpdateStatusWorkingPlanValidator:
--
--     draft                 -> submitted
--     submitted             -> approved | rejected
--     submitted | approved  -> completed | cancelled
--
-- and the last three carry a reason. Everything else is refused.
-- -----------------------------------------------------------------------------

create function set_work_plan_status(
    p_plan_id uuid,
    p_status  work_plan_status,
    p_reason  text default null
)
returns work_plan_status
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
    v_me      uuid := current_salesperson_id();
    v_plan    work_plan;
    v_reason  text := nullif(btrim(coalesce(p_reason, '')), '');
begin
    if v_me is null then
        raise exception 'no salesperson for this account';
    end if;

    select * into v_plan from work_plan where id = p_plan_id;
    if not found then
        raise exception 'plan not found';
    end if;

    -- Definer rights bypass RLS, so ownership is checked by hand. This is the
    -- line that makes the bypass safe.
    if v_plan.salesperson_id <> v_me then
        raise exception 'this is not your plan';
    end if;

    if v_plan.status = p_status then
        return v_plan.status;
    end if;

    case p_status
        when 'submitted' then
            if v_plan.status <> 'draft' then
                raise exception 'only a draft can be sent for approval';
            end if;

        when 'approved', 'rejected' then
            -- Encoded because the rule belongs with the others, not because the
            -- rep can reach it: approving your own plan is the manager's move,
            -- and this app has no manager. It is refused twice over -- once by
            -- the transition, once by the ownership check above.
            raise exception 'approving or rejecting a plan is not done from this app';

        when 'completed' then
            if v_plan.status not in ('submitted', 'approved') then
                raise exception 'only an approved plan can be completed';
            end if;
            if v_reason is null then
                raise exception 'say what came of the plan before closing it';
            end if;

        when 'cancelled' then
            if v_plan.status not in ('draft', 'submitted', 'approved') then
                raise exception 'a % plan cannot be cancelled', v_plan.status;
            end if;
            if v_reason is null then
                raise exception 'a cancelled plan needs a reason';
            end if;

        else
            raise exception 'cannot move a plan to %', p_status;
    end case;

    update work_plan
    set status       = p_status,
        result       = case when p_status = 'completed' then v_reason else result end,
        reason       = case when p_status = 'cancelled' then v_reason else reason end,
        submitted_at = case when p_status = 'submitted' then now() else submitted_at end,
        completed_at = case when p_status = 'completed' then now() else completed_at end,
        cancelled_at = case when p_status = 'cancelled' then now() else cancelled_at end
    where id = p_plan_id;

    insert into work_plan_history (plan_id, status, actor_id, reason)
    values (p_plan_id, p_status, v_me, v_reason);

    return p_status;
end;
$$;

comment on function set_work_plan_status(uuid, work_plan_status, text) is
    'The only way a plan changes state. TaskManagement Status / UpdateStatusTask.';

revoke execute on function set_work_plan_status(uuid, work_plan_status, text) from public;
grant execute on function set_work_plan_status(uuid, work_plan_status, text) to authenticated;

-- -----------------------------------------------------------------------------

create function delete_work_plan(p_plan_id uuid)
returns void
language plpgsql
security invoker
set search_path = public, pg_temp
as $$
declare
    v_status work_plan_status;
begin
    select status into v_status
    from work_plan
    where id = p_plan_id and salesperson_id = current_salesperson_id();

    if not found then
        raise exception 'plan not found';
    end if;

    -- Said out loud rather than left to the delete policy, which would match no
    -- rows and report success.
    if v_status <> 'draft' then
        raise exception 'a % plan cannot be deleted, only cancelled', v_status;
    end if;

    delete from work_plan where id = p_plan_id;
end;
$$;

comment on function delete_work_plan(uuid) is
    'Throws away an unsent draft. TaskManagement Delete.';
