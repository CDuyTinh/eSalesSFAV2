-- =============================================================================
-- What the rep sees of a rejection.
--
-- Since the manager app, a supervisor rejecting a new outlet or a programme
-- sign-up writes why into decision_note. The rep's own lists never returned it,
-- so a rejected row said only "Từ chối" and the rep had to ring to ask. The
-- three readers now carry it; nothing else in them changes.
-- =============================================================================

CREATE OR REPLACE FUNCTION public.customer_registration_detail(p_customer_id uuid)
 RETURNS jsonb
 LANGUAGE sql
 STABLE
 SET search_path TO 'public', 'pg_temp'
AS $function$
    select jsonb_build_object(
        'id', c.id,
        'code', c.code,
        'name', c.name,
        'phone', c.phone,
        'address', c.address,
        'contact_name', c.contact_name,
        'status', c.approval_status,
        'note', c.registration_note,
        'decision_note', c.decision_note,
        'created_at', c.created_at,
        'lat', c.lat,
        'lng', c.lng,
        'avatar_url', c.avatar_url,
        -- The form needs all three levels; the row only stores the ward, so
        -- the other two are walked back up the tree.
        'province_id', d.province_id,
        'district_id', w.district_id,
        'ward_id', c.ward_id,
        'class_id', c.class_id,
        'channel_id', c.channel_id,
        'shop_type_id', c.shop_type_id,
        'photos', coalesce((
            select jsonb_agg(jsonb_build_object(
                'id', p.id,
                'type_id', p.type_id,
                'type_code', t.code,
                'type_name', t.name,
                'storage_path', p.storage_path,
                'taken_at', p.taken_at
            ) order by t.sort_order)
            from customer_registration_photo p
            join customer_photo_type t on t.id = p.type_id
            where p.customer_id = c.id
        ), '[]'::jsonb)
    )
    from customer c
    left join ward w on w.id = c.ward_id
    left join district d on d.id = w.district_id
    where c.id = p_customer_id
      and c.created_by_salesperson_id = current_salesperson_id();
$function$;

CREATE OR REPLACE FUNCTION public.customer_registrations_for(p_statuses customer_approval[] DEFAULT NULL::customer_approval[], p_query text DEFAULT NULL::text, p_limit integer DEFAULT 50, p_offset integer DEFAULT 0)
 RETURNS jsonb
 LANGUAGE sql
 STABLE
 SET search_path TO 'public', 'pg_temp'
AS $function$
    with mine as (
        select
            c.id, c.code, c.name, c.phone, c.address,
            c.approval_status, c.registration_note, c.decision_note, c.created_at,
            c.lat, c.lng, c.avatar_url,
            (select count(*) from customer_registration_photo p
              where p.customer_id = c.id) as photo_count
        from customer c
        where c.created_by_salesperson_id = current_salesperson_id()
    ),
    -- Counts ignore the status filter so a chip keeps its own number while a
    -- different chip is the one filtering.
    filtered as (
        select * from mine
        where (p_statuses is null or approval_status = any (p_statuses))
          and (
              p_query is null
              or btrim(p_query) = ''
              or name  ilike '%' || btrim(p_query) || '%'
              or code  ilike '%' || btrim(p_query) || '%'
              or coalesce(phone, '') ilike '%' || btrim(p_query) || '%'
          )
    ),
    page as (
        select * from filtered
        order by created_at desc
        limit greatest(p_limit, 1) offset greatest(p_offset, 0)
    )
    select jsonb_build_object(
        'total', (select count(*) from filtered),
        'counts', coalesce((
            select jsonb_object_agg(approval_status, n)
            from (
                select approval_status, count(*) as n from mine group by approval_status
            ) s
        ), '{}'::jsonb),
        'items', coalesce((
            select jsonb_agg(jsonb_build_object(
                'id', id,
                'code', code,
                'name', name,
                'phone', phone,
                'address', address,
                'status', approval_status,
                'note', registration_note,
                'decision_note', decision_note,
                'created_at', created_at,
                'avatar_url', avatar_url,
                'photo_count', photo_count
            ) order by created_at desc)
            from page
        ), '[]'::jsonb)
    );
$function$;

CREATE OR REPLACE FUNCTION public.trade_registrations_for(p_kinds trade_kind[] DEFAULT NULL::trade_kind[], p_program_ids uuid[] DEFAULT NULL::uuid[], p_statuses text[] DEFAULT NULL::text[], p_query text DEFAULT NULL::text, p_limit integer DEFAULT 50, p_offset integer DEFAULT 0)
 RETURNS jsonb
 LANGUAGE sql
 STABLE
 SET search_path TO 'public', 'pg_temp'
AS $function$
    with mine as (
        select
            'display'::trade_kind as kind,
            d.id,
            d.registered_at,
            d.status,
            coalesce(d.portion, 1) as portion,
            d.customer_id,
            c.code as customer_code,
            c.name as customer_name,
            c.phone,
            c.address,
            c.avatar_url,
            p.id   as program_id,
            p.code as program_code,
            p.name as program_name,
            lv.name as level_name,
            null::text as reason,
            d.decision_note
        from display_registration d
        join customer c on c.id = d.customer_id
        join display_program p on p.id = d.program_id
        left join display_program_level lv on lv.id = d.level_id
        where d.salesperson_id = current_salesperson_id()
        union all
        select
            'loyalty', l.id, l.registered_at, l.status, coalesce(l.portion, 1),
            l.customer_id, c.code, c.name, c.phone, c.address, c.avatar_url,
            p.id, p.code, p.name, lv.name, l.reason, l.decision_note
        from loyalty_registration l
        join customer c on c.id = l.customer_id
        join loyalty_program p on p.id = l.program_id
        left join loyalty_program_level lv on lv.id = l.level_id
        where l.salesperson_id = current_salesperson_id()
    ),
    filtered as (
        select * from mine
        where (p_kinds is null or kind = any (p_kinds))
          and (p_program_ids is null or program_id = any (p_program_ids))
          and (p_statuses is null or status = any (p_statuses))
          and (
              p_query is null
              or btrim(p_query) = ''
              or customer_name ilike '%' || btrim(p_query) || '%'
              or customer_code ilike '%' || btrim(p_query) || '%'
              or program_name  ilike '%' || btrim(p_query) || '%'
              or coalesce(address, '') ilike '%' || btrim(p_query) || '%'
          )
    ),
    page as (
        select * from filtered
        order by registered_at desc, customer_name
        limit greatest(p_limit, 1) offset greatest(p_offset, 0)
    ),
    slots as (
        select
            coalesce(sum(q.slots), 0)::integer as total,
            coalesce(sum(q.used), 0)::integer  as used
        from (
            select slots, used from display_program_quota
            where salesperson_id = current_salesperson_id()
            union all
            select slots, used from loyalty_program_quota
            where salesperson_id = current_salesperson_id()
        ) q
    )
    select jsonb_build_object(
        'total', (select count(*) from filtered),
        'counts', coalesce((
            select jsonb_object_agg(status, n)
            from (select status, count(*) as n from mine group by status) s
        ), '{}'::jsonb),
        'kind_counts', coalesce((
            select jsonb_object_agg(kind, n)
            from (select kind, count(*) as n from mine group by kind) k
        ), '{}'::jsonb),
        'slots_total', (select total from slots),
        'slots_used', (select used from slots),
        'items', coalesce((
            select jsonb_agg(jsonb_build_object(
                'id', id,
                'kind', kind,
                'registered_at', registered_at,
                'status', status,
                'portion', portion,
                'customer_id', customer_id,
                'customer_code', customer_code,
                'customer_name', customer_name,
                'phone', phone,
                'address', address,
                'avatar_url', avatar_url,
                'program_id', program_id,
                'program_code', program_code,
                'program_name', program_name,
                'level_name', level_name,
                'reason', reason,
                'decision_note', decision_note
            ) order by registered_at desc, customer_name)
            from page
        ), '[]'::jsonb),
        -- The programmes that actually appear, for the filter row. Built from
        -- the rows rather than from the catalogue: a programme the rep has
        -- signed nobody up for is a chip that filters to nothing.
        'programs', coalesce((
            select jsonb_agg(distinct jsonb_build_object(
                'id', program_id,
                'kind', kind,
                'code', program_code,
                'name', program_name
            ))
            from mine
        ), '[]'::jsonb)
    );
$function$;
