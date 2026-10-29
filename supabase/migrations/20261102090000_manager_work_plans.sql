-- =============================================================================
-- Giao việc — the supervisor's side of work_plan
--
-- work_plan was built with this half missing on purpose. It already models a
-- plan somebody else wrote for the rep (`author_id <> salesperson_id`, the
-- legacy's IsAssignToMe) and a 'submitted' state waiting for a manager, and its
-- state machine refuses approve/reject with "not done from this app". The
-- eSalesManager app is where those happen now.
--
-- Legacy: MyTaskController (API_GetTaskListManager, API_GetTaskDetailManager,
-- API_GetTaskCalenderManager, API_GetListTaskTypeManager,
-- API_GetListTaskAssignManager) and TaskApproveController
-- (API_GetTaskApproveManager, Update).
--
-- Three moves, each a security-definer function that checks the team by hand:
--   * assign   — write a plan for a rep. It goes straight to 'approved': the
--                supervisor is the approver, and an assigned task the rep had to
--                send back up for approval would be a round trip to nobody.
--   * decide   — approve or reject a plan the rep submitted (TaskApprove).
--   * cancel   — call off a plan the supervisor assigned, with a reason.
-- Completing stays the rep's move, through set_work_plan_status in SFA.
--
-- Supabase grants EXECUTE on new functions to anon and authenticated by name, so
-- every grant below is spelled out rather than left to `from public`.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- Reading the team's plans directly (photos are signed by the Edge Function and
-- the storage policy for team photos already exists).
-- -----------------------------------------------------------------------------

create policy "manager reads team plans"
    on work_plan for select to authenticated
    using (salesperson_id in (select team_salesperson_ids()));

create policy "manager reads objects of team plans"
    on work_plan_object for select to authenticated
    using (exists (
        select 1 from work_plan p
        where p.id = plan_id and p.salesperson_id in (select team_salesperson_ids())
    ));

create policy "manager reads history of team plans"
    on work_plan_history for select to authenticated
    using (exists (
        select 1 from work_plan p
        where p.id = plan_id and p.salesperson_id in (select team_salesperson_ids())
    ));

create policy "manager reads photos of team plans"
    on work_plan_photo for select to authenticated
    using (exists (
        select 1 from work_plan p
        where p.id = plan_id and p.salesperson_id in (select team_salesperson_ids())
    ));

-- =============================================================================
-- The list (API_GetTaskListManager / API_GetTaskApproveManager)
-- =============================================================================

create function mgr_work_plans(
    p_from_date      date default (current_date - 30),
    p_to_date        date default (current_date + 30),
    p_statuses       work_plan_status[] default null,
    p_salesperson_id uuid default null
)
returns jsonb
language sql
stable
security definer
set search_path = public, pg_temp
as $$
    with matched as (
        select p.*, c.code as category_code, c.name as category_name,
               c.object_kind, c.field_training
        from work_plan p
        join work_with_category c on c.id = p.category_id
        where p.salesperson_id in (select team_salesperson_ids())
          and (p_salesperson_id is null or p.salesperson_id = p_salesperson_id)
          -- A draft is the rep's own notebook until they send it; the
          -- supervisor sees a plan from the moment it is submitted.
          and not (p.status = 'draft' and p.author_id = p.salesperson_id)
          and (p.from_at at time zone 'Asia/Ho_Chi_Minh')::date <= p_to_date
          and (p.to_at   at time zone 'Asia/Ho_Chi_Minh')::date >= p_from_date
    ),
    filtered as (
        select * from matched
        where p_statuses is null or status = any (p_statuses)
    )
    select jsonb_build_object(
        'counts', coalesce((
            select jsonb_object_agg(status, n)
            from (select status, count(*) as n from matched group by status) s
        ), '{}'::jsonb),
        'items', coalesce((
            select jsonb_agg(jsonb_build_object(
                'id',             f.id,
                'salesperson',    staff_brief(f.salesperson_id),
                'author',         staff_brief(f.author_id),
                'assigned_by_me', f.author_id = current_salesperson_id(),
                'category_name',  f.category_name,
                'object_kind',    f.object_kind,
                'field_training', f.field_training,
                'content',        f.content,
                'place',          f.place,
                'from_at',        f.from_at,
                'to_at',          f.to_at,
                'status',         f.status,
                'result',         f.result,
                'reason',         f.reason,
                'object_count',   (select count(*) from work_plan_object o where o.plan_id = f.id)
            ) order by (f.status = 'submitted') desc, f.from_at desc)
            from filtered f
        ), '[]'::jsonb)
    );
$$;

/** One plan in full: work_plan_detail's shape plus who it is for and by. */
create function mgr_work_plan_detail(p_plan_id uuid)
returns jsonb
language sql
stable
security definer
set search_path = public, pg_temp
as $$
    select work_plan_detail(p.id)
        || jsonb_build_object(
               'salesperson',    staff_brief(p.salesperson_id),
               'author',         staff_brief(p.author_id),
               'assigned_by_me', p.author_id = current_salesperson_id(),
               'note',           p.note
           )
    from work_plan p
    where p.id = p_plan_id
      and p.salesperson_id in (select team_salesperson_ids());
$$;

/** The outlets on a team member's routes, for the assign form's picker. */
create function mgr_plannable_customers(p_salesperson_id uuid, p_query text default null)
returns table (id uuid, code text, name text, address text)
language sql
stable
security definer
set search_path = public, pg_temp
as $$
    select distinct c.id, c.code, c.name, c.address
    from customer c
    join route_customer rc on rc.customer_id = c.id and rc.is_active
    join sales_route r on r.id = rc.sales_route_id and r.is_active
    where r.salesperson_id = p_salesperson_id
      and is_team_member(p_salesperson_id)
      and c.is_active
      and (
          p_query is null or btrim(p_query) = ''
          or c.name ilike '%' || btrim(p_query) || '%'
          or c.code ilike '%' || btrim(p_query) || '%'
      )
    order by c.name
    limit 300;
$$;

-- =============================================================================
-- Assign (API_GetListTaskAssignManager's write side)
-- =============================================================================

create function mgr_assign_work_plan(payload jsonb)
returns uuid
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
    v_me          uuid := current_salesperson_id();
    v_rep         uuid := (payload ->> 'salesperson_id')::uuid;
    v_branch      uuid;
    v_category    uuid := (payload ->> 'category_id')::uuid;
    v_object_kind work_plan_object_kind;
    v_from        timestamptz := (payload ->> 'from_at')::timestamptz;
    v_to          timestamptz := (payload ->> 'to_at')::timestamptz;
    v_content     text := btrim(coalesce(payload ->> 'content', ''));
    v_objects     jsonb := coalesce(payload -> 'objects', '[]'::jsonb);
    v_object      jsonb;
    v_target      uuid;
    v_plan_id     uuid;
begin
    if v_rep is null or not is_team_member(v_rep) then
        raise exception 'chọn nhân viên trong nhóm của bạn';
    end if;
    if v_content = '' then
        raise exception 'vui lòng nhập nội dung công việc';
    end if;
    if v_from is null or v_to is null or v_to <= v_from then
        raise exception 'thời gian kết thúc phải sau thời gian bắt đầu';
    end if;

    select object_kind into v_object_kind
    from work_with_category where id = v_category and is_active;
    if not found then
        raise exception 'loại công việc không hợp lệ';
    end if;
    if v_object_kind is not null and jsonb_array_length(v_objects) = 0 then
        raise exception 'loại công việc này cần chọn ít nhất một đối tượng';
    end if;
    if v_object_kind is null and jsonb_array_length(v_objects) > 0 then
        raise exception 'loại công việc này không có đối tượng';
    end if;

    select branch_id into v_branch from salesperson where id = v_rep;

    insert into work_plan (
        salesperson_id, author_id, branch_id, category_id,
        content, note, place, from_at, to_at, status, approved_at
    )
    values (
        v_rep, v_me, v_branch, v_category, v_content,
        nullif(btrim(coalesce(payload ->> 'note', '')), ''),
        nullif(btrim(coalesce(payload ->> 'place', '')), ''),
        v_from, v_to, 'approved', now()
    )
    returning id into v_plan_id;

    for v_object in select * from jsonb_array_elements(v_objects)
    loop
        v_target := (v_object ->> 'id')::uuid;

        -- Each target checked against what the rep can actually act on: their
        -- own outlets, a colleague in the same team, or their own distributor.
        if v_object_kind = 'customer' and not exists (
            select 1 from route_customer rc
            join sales_route r on r.id = rc.sales_route_id and r.is_active
            where rc.customer_id = v_target and rc.is_active and r.salesperson_id = v_rep
        ) then
            raise exception 'khách hàng không thuộc tuyến của nhân viên';
        end if;
        if v_object_kind = 'salesperson'
           and not (is_team_member(v_target) or v_target = v_me) then
            raise exception 'nhân viên đối tượng không thuộc nhóm';
        end if;
        if v_object_kind = 'branch' and v_target <> v_branch then
            raise exception 'nhà phân phối không phải của nhân viên';
        end if;

        insert into work_plan_object (plan_id, kind, customer_id, target_id, target_branch_id)
        values (
            v_plan_id, v_object_kind,
            case when v_object_kind = 'customer'    then v_target end,
            case when v_object_kind = 'salesperson' then v_target end,
            case when v_object_kind = 'branch'      then v_target end
        )
        on conflict do nothing;
    end loop;

    insert into work_plan_history (plan_id, status, actor_id, reason)
    values (v_plan_id, 'approved', v_me, 'Giám sát giao việc');

    return v_plan_id;
end;
$$;

-- =============================================================================
-- Approve / reject what the rep submitted (TaskApprove Update)
-- =============================================================================

create function mgr_decide_work_plan(
    p_plan_id uuid,
    p_approve boolean,
    p_reason  text default null
)
returns work_plan_status
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
    v_plan   work_plan;
    v_reason text := nullif(btrim(coalesce(p_reason, '')), '');
    v_status work_plan_status := case when p_approve then 'approved' else 'rejected' end;
begin
    select * into v_plan from work_plan where id = p_plan_id for update;
    if not found or not is_team_member(v_plan.salesperson_id) then
        raise exception 'không tìm thấy kế hoạch trong nhóm của bạn';
    end if;
    if v_plan.status <> 'submitted' then
        raise exception 'chỉ duyệt được kế hoạch đang chờ duyệt';
    end if;
    if not p_approve and v_reason is null then
        raise exception 'vui lòng nhập lý do từ chối';
    end if;

    update work_plan
       set status      = v_status,
           reason      = case when p_approve then reason else v_reason end,
           approved_at = case when p_approve then now() else approved_at end,
           rejected_at = case when p_approve then rejected_at else now() end
     where id = p_plan_id;

    insert into work_plan_history (plan_id, status, actor_id, reason)
    values (p_plan_id, v_status, current_salesperson_id(), v_reason);

    return v_status;
end;
$$;

-- =============================================================================
-- Call off a task the supervisor assigned
-- =============================================================================

create function mgr_cancel_work_plan(p_plan_id uuid, p_reason text)
returns work_plan_status
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
    v_plan   work_plan;
    v_reason text := nullif(btrim(coalesce(p_reason, '')), '');
begin
    select * into v_plan from work_plan where id = p_plan_id for update;
    if not found or not is_team_member(v_plan.salesperson_id) then
        raise exception 'không tìm thấy kế hoạch trong nhóm của bạn';
    end if;
    if v_plan.author_id is distinct from current_salesperson_id() then
        raise exception 'chỉ huỷ được công việc do bạn giao';
    end if;
    if v_plan.status not in ('submitted', 'approved') then
        raise exception 'công việc đã kết thúc, không thể huỷ';
    end if;
    if v_reason is null then
        raise exception 'vui lòng nhập lý do huỷ';
    end if;

    update work_plan
       set status = 'cancelled', reason = v_reason, cancelled_at = now()
     where id = p_plan_id;

    insert into work_plan_history (plan_id, status, actor_id, reason)
    values (p_plan_id, 'cancelled', current_salesperson_id(), v_reason);

    return 'cancelled';
end;
$$;

-- =============================================================================
-- The approval menu counts plans waiting too
-- =============================================================================

create or replace function mgr_approval_counts()
returns jsonb
language sql
stable
security definer
set search_path = public, pg_temp
as $$
    with team as (select team_salesperson_ids() as id)
    select jsonb_build_object(
        'leave', (select count(*) from leave_request r
                   where r.status = 'pending'
                     and r.salesperson_id in (select id from team)),
        'customer', (select count(*) from customer c
                   where c.approval_status = 'pending'
                     and c.created_by_salesperson_id in (select id from team)),
        'display', (select count(*) from display_registration d
                   where d.status = 'pending'
                     and trade_registration_rep(d.salesperson_id, d.customer_id) in (select id from team)),
        'loyalty', (select count(*) from loyalty_registration l
                   where l.status = 'pending'
                     and trade_registration_rep(l.salesperson_id, l.customer_id) in (select id from team)),
        'posm', (select count(*) from posm_registration p
                   where p.status = 'pending'
                     and trade_registration_rep(p.salesperson_id, p.customer_id) in (select id from team)),
        'work_plan', (select count(*) from work_plan w
                   where w.status = 'submitted'
                     and w.salesperson_id in (select id from team))
    );
$$;

-- -----------------------------------------------------------------------------
-- Grants
-- -----------------------------------------------------------------------------

revoke execute on function mgr_work_plans(date, date, work_plan_status[], uuid) from public, anon;
revoke execute on function mgr_work_plan_detail(uuid) from public, anon;
revoke execute on function mgr_plannable_customers(uuid, text) from public, anon;
revoke execute on function mgr_assign_work_plan(jsonb) from public, anon;
revoke execute on function mgr_decide_work_plan(uuid, boolean, text) from public, anon;
revoke execute on function mgr_cancel_work_plan(uuid, text) from public, anon;
revoke execute on function mgr_approval_counts() from public, anon;

grant execute on function mgr_work_plans(date, date, work_plan_status[], uuid) to authenticated;
grant execute on function mgr_work_plan_detail(uuid) to authenticated;
grant execute on function mgr_plannable_customers(uuid, text) to authenticated;
grant execute on function mgr_assign_work_plan(jsonb) to authenticated;
grant execute on function mgr_decide_work_plan(uuid, boolean, text) to authenticated;
grant execute on function mgr_cancel_work_plan(uuid, text) to authenticated;
grant execute on function mgr_approval_counts() to authenticated;

-- -----------------------------------------------------------------------------
-- Seed: one plan nvbh02 has sent up, so "Chờ duyệt" has something to rule on.
-- -----------------------------------------------------------------------------

do $$
declare
    v_rep  uuid := '00000000-0000-0000-0000-000000000082';  -- nvbh02
    v_cat  uuid := (select id from work_with_category where code = 'WW-TRAIN');
    v_plan uuid;
    v_day  timestamptz := (current_date + 2)::timestamp at time zone 'Asia/Ho_Chi_Minh';
begin
    insert into work_plan (salesperson_id, author_id, branch_id, category_id, content, place,
                           from_at, to_at, status, submitted_at)
    values (v_rep, v_rep, '00000000-0000-0000-0000-000000000010', v_cat,
            'Tham gia đào tạo sản phẩm mì mới', 'Văn phòng NPP Miền Đông',
            v_day + interval '8 hours', v_day + interval '11 hours', 'submitted', now())
    returning id into v_plan;

    insert into work_plan_history (plan_id, status, actor_id) values
        (v_plan, 'draft', v_rep),
        (v_plan, 'submitted', v_rep);
end;
$$;
