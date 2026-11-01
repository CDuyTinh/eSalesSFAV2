-- =============================================================================
-- The team list says which tasks belong to a repeating series.
-- =============================================================================

CREATE OR REPLACE FUNCTION public.mgr_work_plans(p_from_date date DEFAULT (CURRENT_DATE - 30), p_to_date date DEFAULT (CURRENT_DATE + 30), p_statuses work_plan_status[] DEFAULT NULL::work_plan_status[], p_salesperson_id uuid DEFAULT NULL::uuid, p_mine boolean DEFAULT false)
 RETURNS jsonb
 LANGUAGE sql
 STABLE SECURITY DEFINER
 SET search_path TO 'public', 'pg_temp'
AS $function$
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
                'repeats',        f.repeat_group is not null,
                'object_count',   (select count(*) from work_plan_object o where o.plan_id = f.id)
            ) order by (f.status = 'submitted') desc, f.from_at desc)
            from filtered f
        ), '[]'::jsonb)
    );
$function$;

