-- =============================================================================
-- Reading and correcting a registration
-- =============================================================================

create function customer_photo_types()
returns table (
    id          uuid,
    code        text,
    name        text,
    sample_url  text,
    is_required boolean,
    is_avatar   boolean
)
language sql
stable
security invoker
set search_path = public, pg_temp
as $$
    select t.id, t.code, t.name, t.sample_url, t.is_required, t.is_avatar
    from customer_photo_type t
    where t.is_active
    order by t.sort_order, t.code;
$$;

comment on function customer_photo_types() is
    'The photo slots a registration asks for. ConfigSampleImage.';

-- -----------------------------------------------------------------------------

create function customer_registrations_for(
    p_statuses customer_approval[] default null,
    p_query    text default null,
    p_limit    integer default 50,
    p_offset   integer default 0
)
returns jsonb
language sql
stable
security invoker
set search_path = public, pg_temp
as $$
    with mine as (
        select
            c.id, c.code, c.name, c.phone, c.address,
            c.approval_status, c.registration_note, c.created_at,
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
                'created_at', created_at,
                'avatar_url', avatar_url,
                'photo_count', photo_count
            ) order by created_at desc)
            from page
        ), '[]'::jsonb)
    );
$$;

comment on function customer_registrations_for(customer_approval[], text, integer, integer) is
    'Outlets this rep registered, with per-status counts. API FetchNewCustomerList.';

-- -----------------------------------------------------------------------------

create function customer_registration_detail(p_customer_id uuid)
returns jsonb
language sql
stable
security invoker
set search_path = public, pg_temp
as $$
    select jsonb_build_object(
        'id', c.id,
        'code', c.code,
        'name', c.name,
        'phone', c.phone,
        'address', c.address,
        'contact_name', c.contact_name,
        'status', c.approval_status,
        'note', c.registration_note,
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
$$;

comment on function customer_registration_detail(uuid) is
    'One registration in full, with its photos. API FetchNewCustomerID + FetchNewCustomerImage.';

-- -----------------------------------------------------------------------------
-- Correcting one
--
-- Security definer, because the avatar has to be kept in step with the photo
-- slot that claims it and a plain UPDATE from the client could set one without
-- the other. Definer rights mean the ownership and status checks are made by
-- hand here, and said out loud rather than left to a policy that would match no
-- rows and report success.
-- -----------------------------------------------------------------------------

create function update_customer_registration(payload jsonb)
returns jsonb
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
    v_me   uuid := current_salesperson_id();
    v_id   uuid := (payload ->> 'id')::uuid;
    v_row  customer;
    v_name text := btrim(coalesce(payload ->> 'name', ''));
    v_addr text := btrim(coalesce(payload ->> 'address', ''));
begin
    if v_me is null then
        raise exception 'no salesperson for this account';
    end if;

    select * into v_row from customer where id = v_id;

    if not found or v_row.created_by_salesperson_id is distinct from v_me then
        raise exception 'registration not found';
    end if;

    if v_row.approval_status <> 'pending' then
        raise exception 'hồ sơ đã % nên không sửa được', v_row.approval_status;
    end if;

    if v_name = '' then
        raise exception 'name is required';
    end if;

    if v_addr = '' then
        raise exception 'address is required';
    end if;

    update customer
    set name         = v_name,
        address      = v_addr,
        phone        = nullif(btrim(coalesce(payload ->> 'phone', '')), ''),
        contact_name = nullif(btrim(coalesce(payload ->> 'contact_name', '')), ''),
        ward_id      = coalesce(nullif(payload ->> 'ward_id', '')::uuid, ward_id),
        class_id     = coalesce(nullif(payload ->> 'class_id', '')::uuid, class_id),
        channel_id   = coalesce(nullif(payload ->> 'channel_id', '')::uuid, channel_id),
        shop_type_id = coalesce(nullif(payload ->> 'shop_type_id', '')::uuid, shop_type_id),
        registration_note = nullif(btrim(coalesce(payload ->> 'note', '')), '')
    where id = v_id;

    return customer_registration_detail(v_id);
end;
$$;

comment on function update_customer_registration(jsonb) is
    'Corrects a registration still waiting for approval. API UpdateCustomerInfo.';

revoke execute on function update_customer_registration(jsonb) from public;
grant execute on function update_customer_registration(jsonb) to authenticated;

-- -----------------------------------------------------------------------------

/**
 * Files one photo into a slot.
 *
 * Replaces whatever was in that slot, and — when the slot is the one that claims
 * the avatar — sets `customer.avatar_url` in the same statement. Two writes that
 * must not come apart, which is the whole reason this is a function rather than
 * an insert from the client.
 */
create function set_customer_registration_photo(
    p_customer_id  uuid,
    p_type_id      uuid,
    p_storage_path text,
    p_taken_at     timestamptz default now(),
    p_lat          double precision default null,
    p_lng          double precision default null,
    p_file_size    integer default null
)
returns jsonb
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
    v_me        uuid := current_salesperson_id();
    v_row       customer;
    v_is_avatar boolean;
begin
    select * into v_row from customer where id = p_customer_id;

    if not found or v_row.created_by_salesperson_id is distinct from v_me then
        raise exception 'registration not found';
    end if;

    if v_row.approval_status <> 'pending' then
        raise exception 'hồ sơ đã % nên không thêm ảnh được', v_row.approval_status;
    end if;

    select is_avatar into v_is_avatar
    from customer_photo_type where id = p_type_id and is_active;

    if not found then
        raise exception 'unknown photo slot';
    end if;

    insert into customer_registration_photo (
        customer_id, type_id, storage_path, taken_at, lat, lng, file_size
    )
    values (
        p_customer_id, p_type_id, p_storage_path, p_taken_at, p_lat, p_lng, p_file_size
    )
    on conflict (customer_id, type_id) do update
    set storage_path = excluded.storage_path,
        taken_at     = excluded.taken_at,
        lat          = excluded.lat,
        lng          = excluded.lng,
        file_size    = excluded.file_size;

    if v_is_avatar then
        update customer set avatar_url = p_storage_path where id = p_customer_id;
    end if;

    return customer_registration_detail(p_customer_id);
end;
$$;

comment on function set_customer_registration_photo(uuid, uuid, text, timestamptz, double precision, double precision, integer) is
    'Files one photo into its slot, and keeps the avatar in step when that slot claims it.';

revoke execute on function set_customer_registration_photo(uuid, uuid, text, timestamptz, double precision, double precision, integer) from public;
grant execute on function set_customer_registration_photo(uuid, uuid, text, timestamptz, double precision, double precision, integer) to authenticated;

-- -----------------------------------------------------------------------------

create function delete_customer_registration(p_customer_id uuid)
returns void
language plpgsql
security invoker
set search_path = public, pg_temp
as $$
declare
    v_status customer_approval;
begin
    select approval_status into v_status
    from customer
    where id = p_customer_id
      and created_by_salesperson_id = current_salesperson_id();

    if not found then
        raise exception 'registration not found';
    end if;

    -- Said out loud rather than left to the delete policy, which would match no
    -- rows and report success.
    if v_status <> 'pending' then
        raise exception 'hồ sơ đã % nên không xóa được', v_status;
    end if;

    delete from customer where id = p_customer_id;
end;
$$;

comment on function delete_customer_registration(uuid) is
    'Withdraws a registration still waiting for approval. API DelNewCustomer.';
