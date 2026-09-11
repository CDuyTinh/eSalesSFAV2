-- =============================================================================
-- save_work_plan writes history, so it has to be the one holding the pen
--
-- work_plan_history deliberately has no INSERT policy: the point of an audit
-- trail is that the audited party cannot write it. That left save_work_plan,
-- which is where a plan's first 'draft' row comes from, unable to insert -- it
-- ran as the rep and the rep has no such grant.
--
-- The fix is not to open the table. It is to make this function the second and
-- last thing allowed to write history, the way set_work_plan_status already is.
-- Definer rights mean RLS no longer stands behind it, so every check the policy
-- was making is now made here by hand -- including the one the invoker version
-- got for free and the update path was missing: that the rep is not only the
-- owner of the plan but its author. A plan head office wrote for them is theirs
-- to carry out, not to rewrite.
-- =============================================================================

create or replace function save_work_plan(payload jsonb)
returns uuid
language plpgsql
security definer
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
    v_existing   work_plan;
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
        select * into v_existing from work_plan where id = v_plan_id;

        -- Three refusals, each said out loud. Under definer rights none of them
        -- would otherwise happen at all; under invoker rights they happened by
        -- matching no rows, which is silent and reads as success.
        if not found or v_existing.salesperson_id <> v_me then
            raise exception 'plan not found';
        end if;

        if v_existing.author_id is distinct from v_me then
            raise exception 'this plan was written for you, not by you';
        end if;

        if v_existing.status <> 'draft' then
            raise exception 'a % plan can no longer be edited', v_existing.status;
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

revoke execute on function save_work_plan(jsonb) from public;
grant execute on function save_work_plan(jsonb) to authenticated;
