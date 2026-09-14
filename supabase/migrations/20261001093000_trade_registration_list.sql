-- =============================================================================
-- Đăng ký chương trình — the list, its filters and the rep's remaining slots
--
-- API_GetListTradeRegis unions display registrations and loyalty registrations
-- for the rep's route, filtered by type, programme, status and a search key,
-- and returns a header of Target against TotalRegis — how many slots head
-- office gave this rep against how many they have used.
--
-- The rebuild already stores all of it. What was missing is the one place to
-- read it from: registrations were only visible inside the customer hub, one
-- outlet at a time, so a rep could not see what they had signed up this quarter
-- without walking every customer.
--
-- The legacy computes its target by unioning four budget tables. Here it is the
-- quota rows the registration functions already spend against, which is the
-- same number arrived at once rather than twice.
-- =============================================================================

create type trade_kind as enum ('display', 'loyalty');

/**
 * Everything this rep has signed up, newest first.
 *
 * `counts` and `quota` deliberately ignore the filters: a chip showing a count
 * has to keep showing its own number while a different chip is the one
 * filtering, and the slot header describes the rep's allocation rather than
 * whatever slice is on screen.
 */
create function trade_registrations_for(
    p_kinds       trade_kind[] default null,
    p_program_ids uuid[] default null,
    p_statuses    text[] default null,
    p_query       text default null,
    p_limit       integer default 50,
    p_offset      integer default 0
)
returns jsonb
language sql
stable
security invoker
set search_path = public, pg_temp
as $$
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
            null::text as reason
        from display_registration d
        join customer c on c.id = d.customer_id
        join display_program p on p.id = d.program_id
        left join display_program_level lv on lv.id = d.level_id
        where d.salesperson_id = current_salesperson_id()

        union all

        select
            'loyalty', l.id, l.registered_at, l.status, coalesce(l.portion, 1),
            l.customer_id, c.code, c.name, c.phone, c.address, c.avatar_url,
            p.id, p.code, p.name, lv.name, l.reason
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
                'reason', reason
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
$$;

comment on function trade_registrations_for(trade_kind[], uuid[], text[], text, integer, integer) is
    'Every programme registration this rep has made, with filters and their slot allocation. API_GetListTradeRegis.';

-- -----------------------------------------------------------------------------

/**
 * What one outlet could still be signed up for, both kinds at once.
 *
 * The customer hub asks this per kind on two separate tabs; here the rep has
 * picked an outlet and wants to know everything open to it, so the two arrive
 * together and the screen groups them.
 */
create function trade_programs_open_for(p_customer_id uuid)
returns jsonb
language sql
stable
security invoker
set search_path = public, pg_temp
as $$
    select jsonb_build_object(
        'display', coalesce(to_jsonb((select jsonb_agg(d) from display_programs_open_for(p_customer_id) d)), '[]'::jsonb),
        'loyalty', coalesce(to_jsonb((select jsonb_agg(l) from loyalty_open_for(p_customer_id) l)), '[]'::jsonb)
    );
$$;

comment on function trade_programs_open_for(uuid) is
    'Display and loyalty programmes one outlet may still join. FetchListTradeByCust.';
