-- =============================================================================
-- Plans for supervisors: their own, approved by the area manager, and work an
-- area manager hands them.
--
-- The plan tables, approval and cancellation already follow the tree (a
-- supervisor is in their area manager's team), and save_work_plan takes a
-- supervisor's own plan as it takes a rep's. What did not fit a supervisor was
-- the outlets: they have no route of their own, so "the outlets on the
-- assignee's routes" was empty. Their outlets are now those of the reps under
-- them as well — for the picker, and for the check when work is assigned — and
-- the picker serves the caller's own plans too.
-- =============================================================================

/** The outlets on a team member's routes, or under them, for the plan forms' picker. */
create or replace function mgr_plannable_customers(p_salesperson_id uuid, p_query text default null)
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
    where (r.salesperson_id = p_salesperson_id or r.salesperson_id in (select team_reps(p_salesperson_id)))
      and (is_team_member(p_salesperson_id) or p_salesperson_id = current_salesperson_id())
      and c.is_active
      and (
          p_query is null or btrim(p_query) = ''
          or c.name ilike '%' || btrim(p_query) || '%'
          or c.code ilike '%' || btrim(p_query) || '%'
      )
    order by c.name
    limit 300;
$$;

create or replace function mgr_assign_work_plan(payload jsonb)
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

        -- Each target checked against what the assignee can actually act on: their
        -- own outlets (a supervisor's being their reps'), a colleague in the same
        -- team, or their own distributor.
        if v_object_kind = 'customer' and not exists (
            select 1 from route_customer rc
            join sales_route r on r.id = rc.sales_route_id and r.is_active
            where rc.customer_id = v_target and rc.is_active
              and (r.salesperson_id = v_rep or r.salesperson_id in (select team_reps(v_rep)))
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
    values (v_plan_id, 'approved', v_me,
            case when (select position from salesperson where id = v_me) = 'ss' then 'Giám sát giao việc' else 'Quản lý giao việc' end);

    return v_plan_id;
end;
$$;
