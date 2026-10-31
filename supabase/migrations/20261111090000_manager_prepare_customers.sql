-- =============================================================================
-- eSalesManager G11 — Trọng tâm bán hàng, Sửa khách hàng theo nhân viên
--
-- Legacy prepare_sales_focus (API_GetPrepareSalesFocus_Manager): what head
-- office is pushing and how the team is doing on it. Here the team's view of
-- focus_products() — per product, the team's sold quantity and outlets reached,
-- broken down by rep — plus the display and loyalty programmes running that day
-- with how many of the team's outlets have signed up.
--
-- Legacy edit_customer (API_EditCust_Manager_GetCustomerList,
-- API_UpdateCustomer_EmployeeManager): the supervisor picks a rep, then one of
-- the rep's outlets, and corrects its details. Only the fields a supervisor can
-- vouch for from the field are editable; credit limit and approval stay with
-- head office. Every change is kept in customer_change_log, old and new value
-- per field, since an edited outlet has no other trace of who changed what.
-- =============================================================================

create table customer_change_log (
    id          uuid        primary key default gen_random_uuid(),
    customer_id uuid        not null references customer (id) on delete cascade,
    actor_id    uuid        not null references salesperson (id),
    /** { field: { "old": …, "new": … } } for each field that changed. */
    changes     jsonb       not null,
    created_at  timestamptz not null default now()
);

create index on customer_change_log (customer_id, created_at desc);

-- Written and read only through the functions below.
alter table customer_change_log enable row level security;

-- -----------------------------------------------------------------------------
-- Trọng tâm bán hàng
-- -----------------------------------------------------------------------------

create or replace function mgr_sales_focus(p_date date)
returns jsonb
language sql
stable
security definer
set search_path = public, pg_temp
as $$
    with team as (
        select s.id, s.code, s.full_name, s.branch_id
        from salesperson s where s.id in (select team_salesperson_ids())
    ),
    branches as (select distinct branch_id from team),
    focus as (
        select f.id, f.product_id, f.from_date, f.to_date, f.priority, f.target_base_qty, f.note,
               p.code, p.name, p.base_uom
        from focus_product f
        join product p on p.id = f.product_id and p.is_active
        where f.is_active
          and p_date between f.from_date and f.to_date
          and (f.branch_id is null or f.branch_id in (select branch_id from branches))
    ),
    sold as (
        -- Every rep gets a line, the ones who have not sold it yet included:
        -- they are the line the supervisor is looking for.
        select f.id as focus_id, t.id as rep_id, t.code, t.full_name,
               coalesce(x.qty, 0) as qty, coalesce(x.outlets, 0) as outlets
        from focus f
        cross join team t
        left join lateral (
            select sum(l.base_qty) as qty, count(distinct o.customer_id) as outlets
            from sales_order o
            join sales_order_line l on l.order_id = o.id and l.product_id = f.product_id
            where o.salesperson_id = t.id and o.status <> 'cancelled'
              and o.order_date between f.from_date and f.to_date
        ) x on true
    )
    select jsonb_build_object(
        'date', p_date,
        'products', coalesce((
            select jsonb_agg(jsonb_build_object(
                'focus_id', f.id, 'product_code', f.code, 'product_name', f.name, 'base_uom', f.base_uom,
                'from_date', f.from_date, 'to_date', f.to_date, 'priority', f.priority,
                -- The target is per rep in focus_product; the team's is the sum.
                'target_base_qty', f.target_base_qty,
                'team_target', f.target_base_qty * (select count(*) from team),
                'note', f.note,
                'sold_base_qty', (select coalesce(sum(s.qty), 0) from sold s where s.focus_id = f.id),
                -- Distinct across the team: two reps share outlets, so summing theirs
                -- would count a shop twice.
                'outlets', (select count(distinct o.customer_id)
                            from sales_order o
                            join sales_order_line l on l.order_id = o.id and l.product_id = f.product_id
                            where o.salesperson_id in (select id from team) and o.status <> 'cancelled'
                              and o.order_date between f.from_date and f.to_date),
                'reps', (select coalesce(jsonb_agg(jsonb_build_object(
                             'code', s.code, 'name', s.full_name, 'sold_base_qty', s.qty, 'outlets', s.outlets)
                             order by s.code), '[]'::jsonb)
                         from sold s where s.focus_id = f.id)
            ) order by f.priority, f.name)
            from focus f
        ), '[]'::jsonb),
        'programs', coalesce((
            select jsonb_agg(x order by x ->> 'kind', x ->> 'code')
            from (
                select jsonb_build_object(
                    'kind', 'display', 'code', d.code, 'name', d.name,
                    'from_date', d.from_date, 'to_date', d.to_date,
                    'registered', (select count(distinct r.customer_id) from display_registration r
                                   where r.program_id = d.id and r.status in ('pending', 'approved')
                                     and trade_registration_rep(r.salesperson_id, r.customer_id) in (select id from team))
                ) as x
                from display_program d
                where d.is_active and p_date between d.from_date and d.to_date
                  and (d.branch_id is null or d.branch_id in (select branch_id from branches))
                union all
                select jsonb_build_object(
                    'kind', 'loyalty', 'code', l.code, 'name', l.name,
                    'from_date', l.from_date, 'to_date', l.to_date,
                    'registered', (select count(distinct r.customer_id) from loyalty_registration r
                                   where r.program_id = l.id and r.status in ('pending', 'approved')
                                     and trade_registration_rep(r.salesperson_id, r.customer_id) in (select id from team))
                )
                from loyalty_program l
                where l.is_active and p_date between l.from_date and l.to_date
                  and (l.branch_id is null or l.branch_id in (select branch_id from branches))
            ) p
        ), '[]'::jsonb)
    );
$$;

-- -----------------------------------------------------------------------------
-- Sửa khách hàng theo nhân viên
-- -----------------------------------------------------------------------------

/** Whether the outlet is on an active route of someone in the caller's team. Internal. */
create or replace function team_outlet(p_customer_id uuid)
returns boolean
language sql
stable
security definer
set search_path = public, pg_temp
as $$
    select exists (
        select 1 from route_customer rc
        join sales_route r on r.id = rc.sales_route_id and r.is_active
        where rc.customer_id = p_customer_id and rc.is_active
          and r.salesperson_id in (select team_salesperson_ids())
    );
$$;

/** A rep's outlets, as the legacy customer list after picking an employee. */
create or replace function mgr_team_customers(p_rep uuid, p_query text default null)
returns jsonb
language plpgsql
stable
security definer
set search_path = public, pg_temp
as $$
declare
    v_q text := nullif(btrim(coalesce(p_query, '')), '');
begin
    if p_rep is null or not is_team_member(p_rep) then
        raise exception 'chọn nhân viên trong nhóm của bạn';
    end if;
    return coalesce((
        select jsonb_agg(jsonb_build_object(
            'id', c.id, 'code', c.code, 'name', c.name, 'address', c.address,
            'phone', c.phone, 'contact_name', c.contact_name,
            'routes', x.routes,
            'edited_at', (select max(l.created_at) from customer_change_log l where l.customer_id = c.id)
        ) order by c.code)
        from (
            select rc.customer_id, string_agg(distinct r.code, ', ') as routes
            from route_customer rc
            join sales_route r on r.id = rc.sales_route_id and r.is_active
            where rc.is_active and r.salesperson_id = p_rep
            group by rc.customer_id
        ) x
        join customer c on c.id = x.customer_id and c.is_active
        where v_q is null
           or c.code ilike '%' || v_q || '%'
           or c.name ilike '%' || v_q || '%'
           or c.address ilike '%' || v_q || '%'
           or c.phone ilike '%' || v_q || '%'
    ), '[]'::jsonb);
end;
$$;

/** One outlet's editable fields, the option lists, and what was changed before. */
create or replace function mgr_customer_edit(p_customer_id uuid)
returns jsonb
language plpgsql
stable
security definer
set search_path = public, pg_temp
as $$
begin
    if not team_outlet(p_customer_id) then
        raise exception 'điểm bán không thuộc tuyến của nhóm bạn';
    end if;
    return (
        select jsonb_build_object(
            'id', c.id, 'code', c.code, 'name', c.name, 'contact_name', c.contact_name,
            'phone', c.phone, 'address', c.address, 'lat', c.lat, 'lng', c.lng,
            'class_id', c.class_id, 'channel_id', c.channel_id, 'shop_type_id', c.shop_type_id,
            'options', jsonb_build_object(
                'classes',    (select coalesce(jsonb_agg(jsonb_build_object('id', id, 'name', name) order by name), '[]') from customer_class),
                'channels',   (select coalesce(jsonb_agg(jsonb_build_object('id', id, 'name', name) order by name), '[]') from sales_channel),
                'shop_types', (select coalesce(jsonb_agg(jsonb_build_object('id', id, 'name', name) order by name), '[]') from shop_type)),
            'history', (
                select coalesce(jsonb_agg(jsonb_build_object(
                    'at', l.created_at, 'actor', s.full_name, 'changes', l.changes) order by l.created_at desc), '[]')
                from (select * from customer_change_log where customer_id = c.id order by created_at desc limit 20) l
                join salesperson s on s.id = l.actor_id)
        )
        from customer c where c.id = p_customer_id
    );
end;
$$;

/**
 * payload: any of name, contact_name, phone, address, class_id, channel_id,
 * shop_type_id, lat + lng. A field left out is left alone; one sent equal to
 * what is stored is not logged.
 */
create or replace function mgr_update_customer(p_customer_id uuid, payload jsonb)
returns jsonb
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
    c         customer;
    v_new     customer;
    v_changes jsonb := '{}'::jsonb;
    v_field   text;
begin
    if not team_outlet(p_customer_id) then
        raise exception 'điểm bán không thuộc tuyến của nhóm bạn';
    end if;
    select * into c from customer where id = p_customer_id for update;
    v_new := c;

    if payload ? 'name' then
        v_new.name := nullif(btrim(payload ->> 'name'), '');
        if v_new.name is null then raise exception 'tên điểm bán không được để trống'; end if;
    end if;
    if payload ? 'contact_name' then v_new.contact_name := nullif(btrim(payload ->> 'contact_name'), ''); end if;
    if payload ? 'phone' then
        -- Spaces, dots and dashes are formatting and dropped; anything else is
        -- refused rather than stripped, so "x" cannot quietly erase a number.
        -- Only an empty value clears it.
        v_new.phone := nullif(regexp_replace(btrim(coalesce(payload ->> 'phone', '')), '[ .\-]', '', 'g'), '');
        if v_new.phone is not null and v_new.phone !~ '^\+?[0-9]{9,12}$' then
            raise exception 'số điện thoại không hợp lệ';
        end if;
    end if;
    if payload ? 'address' then
        v_new.address := nullif(btrim(payload ->> 'address'), '');
        if v_new.address is null then raise exception 'địa chỉ không được để trống'; end if;
    end if;
    if payload ? 'class_id' then
        v_new.class_id := (payload ->> 'class_id')::uuid;
        if v_new.class_id is not null and not exists (select 1 from customer_class where id = v_new.class_id) then
            raise exception 'hạng điểm bán không hợp lệ';
        end if;
    end if;
    if payload ? 'channel_id' then
        v_new.channel_id := (payload ->> 'channel_id')::uuid;
        if v_new.channel_id is not null and not exists (select 1 from sales_channel where id = v_new.channel_id) then
            raise exception 'kênh không hợp lệ';
        end if;
    end if;
    if payload ? 'shop_type_id' then
        v_new.shop_type_id := (payload ->> 'shop_type_id')::uuid;
        if v_new.shop_type_id is not null and not exists (select 1 from shop_type where id = v_new.shop_type_id) then
            raise exception 'loại cửa hàng không hợp lệ';
        end if;
    end if;
    if payload ? 'lat' or payload ? 'lng' then
        v_new.lat := (payload ->> 'lat')::double precision;
        v_new.lng := (payload ->> 'lng')::double precision;
        if v_new.lat is null or v_new.lng is null
           or v_new.lat not between -90 and 90 or v_new.lng not between -180 and 180 then
            raise exception 'toạ độ không hợp lệ';
        end if;
    end if;

    foreach v_field in array array['name', 'contact_name', 'phone', 'address', 'class_id', 'channel_id', 'shop_type_id', 'lat', 'lng']
    loop
        if (to_jsonb(c) -> v_field) is distinct from (to_jsonb(v_new) -> v_field) then
            v_changes := v_changes || jsonb_build_object(v_field,
                jsonb_build_object('old', to_jsonb(c) -> v_field, 'new', to_jsonb(v_new) -> v_field));
        end if;
    end loop;

    if v_changes = '{}'::jsonb then
        return jsonb_build_object('changed', 0);
    end if;

    update customer
    set name = v_new.name, contact_name = v_new.contact_name, phone = v_new.phone, address = v_new.address,
        class_id = v_new.class_id, channel_id = v_new.channel_id, shop_type_id = v_new.shop_type_id,
        lat = v_new.lat, lng = v_new.lng, updated_at = now()
    where id = p_customer_id;

    insert into customer_change_log (customer_id, actor_id, changes)
    values (p_customer_id, current_salesperson_id(), v_changes);

    return jsonb_build_object('changed', (select count(*) from jsonb_object_keys(v_changes)));
end;
$$;

revoke execute on function team_outlet(uuid) from public, anon, authenticated;
revoke execute on function mgr_sales_focus(date) from public, anon;
revoke execute on function mgr_team_customers(uuid, text) from public, anon;
revoke execute on function mgr_customer_edit(uuid) from public, anon;
revoke execute on function mgr_update_customer(uuid, jsonb) from public, anon;

grant execute on function mgr_sales_focus(date) to authenticated;
grant execute on function mgr_team_customers(uuid, text) to authenticated;
grant execute on function mgr_customer_edit(uuid) to authenticated;
grant execute on function mgr_update_customer(uuid, jsonb) to authenticated;
