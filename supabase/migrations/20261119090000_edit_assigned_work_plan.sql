-- =============================================================================
-- Sửa việc đã giao.
--
-- A task handed down could only be called off and handed down again, which
-- lost its history and told the rep twice. The one who assigned it may now
-- change it while it is still to be done (approved, nothing reported): the
-- work type, words, place, times and objects, checked exactly as when it was
-- assigned. The person it is for stays the same; handing it to someone else
-- is still a cancel and a new task. The history records that it was changed.
-- =============================================================================

create function mgr_update_work_plan(p_plan_id uuid, payload jsonb)
returns uuid
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
    v_me          uuid := current_salesperson_id();
    v_plan        work_plan;
    v_branch      uuid;
    v_category    uuid := (payload ->> 'category_id')::uuid;
    v_object_kind work_plan_object_kind;
    v_from        timestamptz := (payload ->> 'from_at')::timestamptz;
    v_to          timestamptz := (payload ->> 'to_at')::timestamptz;
    v_content     text := btrim(coalesce(payload ->> 'content', ''));
    v_objects     jsonb := coalesce(payload -> 'objects', '[]'::jsonb);
    v_object      jsonb;
    v_target      uuid;
begin
    select * into v_plan from work_plan where id = p_plan_id for update;
    if not found or not is_team_member(v_plan.salesperson_id) then
        raise exception 'không tìm thấy công việc trong nhóm của bạn';
    end if;
    if v_plan.author_id is distinct from v_me then
        raise exception 'chỉ sửa được công việc do bạn giao';
    end if;
    if v_plan.status <> 'approved' then
        raise exception 'công việc đã kết thúc, không sửa được nữa';
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

    select branch_id into v_branch from salesperson where id = v_plan.salesperson_id;

    update work_plan
    set category_id = v_category,
        content     = v_content,
        note        = nullif(btrim(coalesce(payload ->> 'note', '')), ''),
        place       = nullif(btrim(coalesce(payload ->> 'place', '')), ''),
        from_at     = v_from,
        to_at       = v_to
    where id = v_plan.id;

    delete from work_plan_object where plan_id = v_plan.id;

    for v_object in select * from jsonb_array_elements(v_objects)
    loop
        v_target := (v_object ->> 'id')::uuid;

        -- The same checks as mgr_assign_work_plan: what the assignee can act on.
        if v_object_kind = 'customer' and not exists (
            select 1 from route_customer rc
            join sales_route r on r.id = rc.sales_route_id and r.is_active
            where rc.customer_id = v_target and rc.is_active
              and (r.salesperson_id = v_plan.salesperson_id
                   or r.salesperson_id in (select team_reps(v_plan.salesperson_id)))
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
            v_plan.id, v_object_kind,
            case when v_object_kind = 'customer'    then v_target end,
            case when v_object_kind = 'salesperson' then v_target end,
            case when v_object_kind = 'branch'      then v_target end
        )
        on conflict do nothing;
    end loop;

    insert into work_plan_history (plan_id, status, actor_id, reason)
    values (v_plan.id, 'approved', v_me, 'Sửa công việc đã giao');

    return v_plan.id;
end;
$$;

revoke execute on function mgr_update_work_plan(uuid, jsonb) from public, anon;
grant execute on function mgr_update_work_plan(uuid, jsonb) to authenticated;
