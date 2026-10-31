-- =============================================================================
-- A manager's own plans, in the manager app.
--
-- A supervisor plans their own days as a rep does, and their area manager
-- approves them through the team list they already have. Writing and moving
-- an own plan uses the rep's functions (save_work_plan, set_work_plan_status);
-- what the manager app lacked was a way to list and open them, since the team
-- list and detail only look below the caller. mgr_work_plans takes p_mine for
-- the caller's own plans, drafts included, and the detail opens an own plan.
-- =============================================================================

drop function mgr_work_plans(date, date, work_plan_status[], uuid);

create function mgr_work_plans(
    p_from_date      date default (current_date - 30),
    p_to_date        date default (current_date + 30),
    p_statuses       work_plan_status[] default null,
    p_salesperson_id uuid default null,
    p_mine           boolean default false
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
        where case
                  -- The caller's own plans, their drafts included.
                  when p_mine then p.salesperson_id = current_salesperson_id()
                  -- A draft is the rep's own notebook until they send it; the
                  -- supervisor sees a plan from the moment it is submitted.
                  else p.salesperson_id in (select team_salesperson_ids())
                       and (p_salesperson_id is null or p.salesperson_id = p_salesperson_id)
                       and not (p.status = 'draft' and p.author_id = p.salesperson_id)
              end
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
create or replace function mgr_work_plan_detail(p_plan_id uuid)
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
      and (p.salesperson_id in (select team_salesperson_ids()) or p.salesperson_id = current_salesperson_id());
$$;

revoke execute on function mgr_work_plans(date, date, work_plan_status[], uuid, boolean) from public, anon;
grant execute on function mgr_work_plans(date, date, work_plan_status[], uuid, boolean) to authenticated;
