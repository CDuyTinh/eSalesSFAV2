-- =============================================================================
-- Phê duyệt — the supervisor's half of every "pending" the SFA app creates
--
-- The rep side has been filing things for weeks that nobody in the app could
-- rule on: leave (leave_request.status), outlets registered in the field
-- (customer.approval_status), and sign-ups for display, loyalty and POSM
-- programmes. The SFA migrations said so in as many words — "head office decides
-- through the back office". The eSalesManager app is that somebody now, for the
-- reps in the supervisor's own team.
--
-- Legacy: ApproveNewLeaveController, ApproveNewCustomerController
-- (ApproveCustomer / ApproveCustomerGenMCP), ApproveNewTradeProgramController,
-- procs API_GetApproveLeaveManager, API_GetNewCustomerListManager,
-- API_GetApproveTradeProgramList, API_GetRejectReasonList.
--
-- Shape of it:
--   * every pending table gets who decided, when, and the note they left;
--   * the manager can read the team's rows (additive SELECT policies);
--   * every read and every decision goes through a security-definer function
--     that checks team membership by hand, so a manager cannot rule on a rep
--     who is not theirs even by guessing an id.
--
-- A refusal needs a reason. The legacy picked one from a list
-- (API_GetRejectReasonList); free text is kept here until there is a list worth
-- maintaining, and the rep sees it either way.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- Who decided
-- -----------------------------------------------------------------------------

alter table leave_request
    add column decided_by uuid references salesperson (id);

alter table customer
    add column decision_note text,
    add column decided_at    timestamptz,
    add column decided_by    uuid references salesperson (id);

alter table display_registration
    add column decision_note text,
    add column decided_at    timestamptz,
    add column decided_by    uuid references salesperson (id);

alter table loyalty_registration
    add column decision_note text,
    add column decided_at    timestamptz,
    add column decided_by    uuid references salesperson (id);

alter table posm_registration
    add column decision_note text,
    add column decided_at    timestamptz,
    add column decided_by    uuid references salesperson (id);

-- -----------------------------------------------------------------------------
-- Leave: let the supervisor through the rep-transition guard
--
-- The guard only ever let the service role move a request past 'pending'. A
-- supervisor deciding for their own team is now the other legitimate writer.
-- -----------------------------------------------------------------------------

create or replace function leave_request_rep_transition()
returns trigger
language plpgsql
security definer
set search_path = public, pg_temp
as $$
begin
    if (select auth.uid()) is null then
        return new;
    end if;

    if new.status is distinct from old.status
       and is_team_member(old.salesperson_id) then
        return new;
    end if;

    if new.status is distinct from old.status
       and not (old.status = 'pending' and new.status = 'cancelled') then
        raise exception 'chỉ có thể huỷ đơn đang chờ duyệt';
    end if;

    return new;
end;
$$;

-- -----------------------------------------------------------------------------
-- What the supervisor may read directly
-- -----------------------------------------------------------------------------

create policy "manager reads team leave"
    on leave_request for select to authenticated
    using (salesperson_id in (select team_salesperson_ids()));

create policy "manager reads outlets the team registered"
    on customer for select to authenticated
    using (created_by_salesperson_id in (select team_salesperson_ids()));

create policy "manager reads team registration photos"
    on customer_registration_photo for select to authenticated
    using (exists (
        select 1 from customer c
        where c.id = customer_id
          and c.created_by_salesperson_id in (select team_salesperson_ids())
    ));

-- Photo objects are filed under <salesperson_id>/…, which is what the rep's own
-- policy authorises on; the supervisor gets the same test against the team.
create policy "manager reads team visit photos"
    on storage.objects for select to authenticated
    using (
        bucket_id = 'visit-photos'
        and (storage.foldername(name))[1] in (
            select t::text from team_salesperson_ids() t
        )
    );

-- -----------------------------------------------------------------------------
-- Whose registration is it
--
-- Programme sign-ups made in the app carry salesperson_id. Older rows, and ones
-- head office loaded, do not; those belong to whoever has the outlet on an
-- active route, which is who the legacy would have shown them to.
-- -----------------------------------------------------------------------------

create function trade_registration_rep(p_salesperson_id uuid, p_customer_id uuid)
returns uuid
language sql
stable
security definer
set search_path = public, pg_temp
as $$
    select coalesce(
        p_salesperson_id,
        (select sr.salesperson_id
           from route_customer rc
           join sales_route sr on sr.id = rc.sales_route_id
          where rc.customer_id = p_customer_id
            and rc.is_active and sr.is_active
          order by sr.created_at
          limit 1)
    );
$$;

revoke execute on function trade_registration_rep(uuid, uuid) from public;

-- -----------------------------------------------------------------------------
-- Small helper: a rep as the screens show one
-- -----------------------------------------------------------------------------

create function staff_brief(p_id uuid)
returns jsonb
language sql
stable
security definer
set search_path = public, pg_temp
as $$
    select jsonb_build_object('id', s.id, 'code', s.code, 'name', s.full_name)
    from salesperson s where s.id = p_id;
$$;

revoke execute on function staff_brief(uuid) from public;

-- =============================================================================
-- Counts for the approval menu (API_GetApproveRegisCount)
-- =============================================================================

create function mgr_approval_counts()
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
                     and trade_registration_rep(p.salesperson_id, p.customer_id) in (select id from team))
    );
$$;

-- =============================================================================
-- Leave
-- =============================================================================

create function mgr_leave_list(
    p_statuses       text[] default null,
    p_salesperson_id uuid   default null
)
returns jsonb
language sql
stable
security definer
set search_path = public, pg_temp
as $$
    with mine as (
        select r.*, t.name as type_name, t.is_paid
        from leave_request r
        join leave_type t on t.id = r.leave_type_id
        where r.salesperson_id in (select team_salesperson_ids())
          and (p_salesperson_id is null or r.salesperson_id = p_salesperson_id)
    ),
    filtered as (
        select * from mine
        where p_statuses is null or status::text = any (p_statuses)
    )
    select jsonb_build_object(
        'counts', coalesce((
            select jsonb_object_agg(status, n)
            from (select status, count(*) as n from mine group by status) s
        ), '{}'::jsonb),
        'items', coalesce((
            select jsonb_agg(jsonb_build_object(
                'id',            f.id,
                'salesperson',   staff_brief(f.salesperson_id),
                'type_name',     f.type_name,
                'is_paid',       f.is_paid,
                'from_date',     f.from_date,
                'to_date',       f.to_date,
                'days',          f.to_date - f.from_date + 1,
                'reason',        f.reason,
                'status',        f.status,
                'decision_note', f.decision_note,
                'decided_at',    f.decided_at,
                'created_at',    f.created_at
            ) order by (f.status = 'pending') desc, f.from_date desc)
            from filtered f
        ), '[]'::jsonb)
    );
$$;

create function mgr_decide_leave(
    p_id      uuid,
    p_approve boolean,
    p_note    text default null
)
returns jsonb
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
    v_row  leave_request;
    v_note text := nullif(btrim(coalesce(p_note, '')), '');
begin
    select * into v_row from leave_request where id = p_id for update;

    if v_row.id is null or not is_team_member(v_row.salesperson_id) then
        raise exception 'không tìm thấy đơn nghỉ phép trong nhóm của bạn';
    end if;
    if v_row.status <> 'pending' then
        raise exception 'đơn này đã được xử lý';
    end if;
    if not p_approve and v_note is null then
        raise exception 'vui lòng nhập lý do từ chối';
    end if;

    update leave_request
       set status        = case when p_approve then 'approved' else 'rejected' end::leave_status,
           decision_note = v_note,
           decided_at    = now(),
           decided_by    = current_salesperson_id()
     where id = p_id;

    return jsonb_build_object('id', p_id,
        'status', case when p_approve then 'approved' else 'rejected' end);
end;
$$;

-- =============================================================================
-- Outlets registered in the field
-- =============================================================================

create function mgr_customer_list(
    p_statuses text[] default null,
    p_query    text   default null
)
returns jsonb
language sql
stable
security definer
set search_path = public, pg_temp
as $$
    with mine as (
        select c.*,
               (select count(*) from customer_registration_photo p
                 where p.customer_id = c.id) as photo_count
        from customer c
        where c.created_by_salesperson_id in (select team_salesperson_ids())
    ),
    filtered as (
        select * from mine
        where (p_statuses is null or approval_status::text = any (p_statuses))
          and (
              p_query is null or btrim(p_query) = ''
              or name ilike '%' || btrim(p_query) || '%'
              or code ilike '%' || btrim(p_query) || '%'
              or coalesce(phone, '') ilike '%' || btrim(p_query) || '%'
          )
    )
    select jsonb_build_object(
        'counts', coalesce((
            select jsonb_object_agg(approval_status, n)
            from (select approval_status, count(*) as n from mine group by approval_status) s
        ), '{}'::jsonb),
        'items', coalesce((
            select jsonb_agg(jsonb_build_object(
                'id',            f.id,
                'code',          f.code,
                'name',          f.name,
                'phone',         f.phone,
                'address',       f.address,
                'status',        f.approval_status,
                'note',          f.registration_note,
                'decision_note', f.decision_note,
                'created_at',    f.created_at,
                'photo_count',   f.photo_count,
                'salesperson',   staff_brief(f.created_by_salesperson_id)
            ) order by (f.approval_status = 'pending') desc, f.created_at desc)
            from filtered f
        ), '[]'::jsonb)
    );
$$;

create function mgr_customer_detail(p_id uuid)
returns jsonb
language sql
stable
security definer
set search_path = public, pg_temp
as $$
    select jsonb_build_object(
        'id',            c.id,
        'code',          c.code,
        'name',          c.name,
        'phone',         c.phone,
        'address',       c.address,
        'contact_name',  c.contact_name,
        'lat',           c.lat,
        'lng',           c.lng,
        'status',        c.approval_status,
        'note',          c.registration_note,
        'decision_note', c.decision_note,
        'decided_at',    c.decided_at,
        'created_at',    c.created_at,
        'salesperson',   staff_brief(c.created_by_salesperson_id),
        'ward',          w.name,
        'district',      d.name,
        'province',      pv.name,
        'class_name',    cc.name,
        'channel_name',  ch.name,
        'shop_type_name', st.name,
        'photos', coalesce((
            select jsonb_agg(jsonb_build_object(
                'type_name',    t.name,
                'storage_path', p.storage_path,
                'taken_at',     p.taken_at
            ) order by t.sort_order)
            from customer_registration_photo p
            join customer_photo_type t on t.id = p.type_id
            where p.customer_id = c.id
        ), '[]'::jsonb),
        -- Where the outlet can be put on approval: the registering rep's routes.
        'routes', coalesce((
            select jsonb_agg(jsonb_build_object(
                'id', sr.id, 'code', sr.code, 'name', sr.name,
                'already_on', exists (
                    select 1 from route_customer rc
                    where rc.sales_route_id = sr.id and rc.customer_id = c.id
                )
            ) order by sr.code)
            from sales_route sr
            where sr.salesperson_id = c.created_by_salesperson_id
              and sr.is_active
        ), '[]'::jsonb)
    )
    from customer c
    left join ward w on w.id = c.ward_id
    left join district d on d.id = w.district_id
    left join province pv on pv.id = d.province_id
    left join customer_class cc on cc.id = c.class_id
    left join sales_channel ch on ch.id = c.channel_id
    left join shop_type st on st.id = c.shop_type_id
    where c.id = p_id
      and c.created_by_salesperson_id in (select team_salesperson_ids());
$$;

/**
 * Approving can also put the outlet on one of the rep's routes, which is what
 * the legacy's ApproveCustomerGenMCP did in the same call: an approved outlet
 * that is on no route is one nobody will visit.
 */
create function mgr_decide_customer(
    p_id       uuid,
    p_approve  boolean,
    p_note     text default null,
    p_route_id uuid default null
)
returns jsonb
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
    v_row  customer;
    v_note text := nullif(btrim(coalesce(p_note, '')), '');
    v_next integer;
begin
    select * into v_row from customer where id = p_id for update;

    if v_row.id is null or not is_team_member(v_row.created_by_salesperson_id) then
        raise exception 'không tìm thấy khách hàng đăng ký trong nhóm của bạn';
    end if;
    if v_row.approval_status <> 'pending' then
        raise exception 'khách hàng này đã được xử lý';
    end if;
    if not p_approve and v_note is null then
        raise exception 'vui lòng nhập lý do từ chối';
    end if;

    if p_approve and p_route_id is not null then
        if not exists (
            select 1 from sales_route
            where id = p_route_id
              and salesperson_id = v_row.created_by_salesperson_id
              and is_active
        ) then
            raise exception 'tuyến không thuộc nhân viên đã đăng ký khách hàng';
        end if;

        select coalesce(max(visit_order), 0) + 1 into v_next
          from route_customer where sales_route_id = p_route_id;

        insert into route_customer (sales_route_id, customer_id, visit_order)
        values (p_route_id, p_id, v_next)
        on conflict (sales_route_id, customer_id) do update set is_active = true;
    end if;

    update customer
       set approval_status = case when p_approve then 'approved' else 'rejected' end::customer_approval,
           decision_note   = v_note,
           decided_at      = now(),
           decided_by      = current_salesperson_id()
     where id = p_id;

    return jsonb_build_object('id', p_id,
        'status', case when p_approve then 'approved' else 'rejected' end);
end;
$$;

-- =============================================================================
-- Programme sign-ups: display, loyalty, POSM
-- =============================================================================

create function mgr_trade_list(
    p_kinds    text[] default null,
    p_statuses text[] default null
)
returns jsonb
language sql
stable
security definer
set search_path = public, pg_temp
as $$
    with mine as (
        select 'display'::text as kind, d.id, d.status, d.registered_at, d.created_at,
               d.customer_id, p.code as program_code, p.name as program_name,
               lv.name as detail_name, coalesce(d.portion, 1) as qty,
               null::integer as approved_qty, null::text as reason,
               d.decision_note, d.decided_at,
               trade_registration_rep(d.salesperson_id, d.customer_id) as rep_id
        from display_registration d
        join display_program p on p.id = d.program_id
        join display_program_level lv on lv.id = d.level_id

        union all

        select 'loyalty', l.id, l.status, l.registered_at, l.created_at,
               l.customer_id, p.code, p.name,
               lv.name, l.portion, null, l.reason,
               l.decision_note, l.decided_at,
               trade_registration_rep(l.salesperson_id, l.customer_id)
        from loyalty_registration l
        join loyalty_program p on p.id = l.program_id
        join loyalty_program_level lv on lv.id = l.level_id

        union all

        select 'posm', r.id, r.status, r.registered_at, r.created_at,
               r.customer_id, p.code, p.name,
               i.name, r.regis_qty, r.approved_qty, r.reason,
               r.decision_note, r.decided_at,
               trade_registration_rep(r.salesperson_id, r.customer_id)
        from posm_registration r
        join posm_program p on p.id = r.program_id
        join posm_item i on i.id = r.posm_item_id
    ),
    team_rows as (
        select m.* from mine m
        where m.rep_id in (select team_salesperson_ids())
    ),
    filtered as (
        select * from team_rows
        where (p_kinds is null or kind = any (p_kinds))
          and (p_statuses is null or status = any (p_statuses))
    )
    select jsonb_build_object(
        'counts', coalesce((
            select jsonb_object_agg(status, n)
            from (
                select status, count(*) as n from team_rows
                where p_kinds is null or kind = any (p_kinds)
                group by status
            ) s
        ), '{}'::jsonb),
        'items', coalesce((
            select jsonb_agg(jsonb_build_object(
                'kind',          f.kind,
                'id',            f.id,
                'status',        f.status,
                'registered_at', f.registered_at,
                'program_code',  f.program_code,
                'program_name',  f.program_name,
                'detail_name',   f.detail_name,
                'qty',           f.qty,
                'approved_qty',  f.approved_qty,
                'reason',        f.reason,
                'decision_note', f.decision_note,
                'decided_at',    f.decided_at,
                'customer', jsonb_build_object(
                    'id', c.id, 'code', c.code, 'name', c.name, 'address', c.address),
                'salesperson',   staff_brief(f.rep_id)
            ) order by (f.status = 'pending') desc, f.created_at desc)
            from filtered f
            join customer c on c.id = f.customer_id
        ), '[]'::jsonb)
    );
$$;

/**
 * Refusing a display or loyalty sign-up hands the slot back to the rep: the
 * registration spent it, and a refused outlet must not keep a colleague's
 * allocation tied up. POSM has no slots; approving it settles approved_qty,
 * which defaults to what was asked for.
 */
create function mgr_decide_trade(
    p_kind         text,
    p_id           uuid,
    p_approve      boolean,
    p_note         text    default null,
    p_approved_qty integer default null
)
returns jsonb
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
    v_note   text := nullif(btrim(coalesce(p_note, '')), '');
    v_status text := case when p_approve then 'approved' else 'rejected' end;
    v_me     uuid := current_salesperson_id();
    v_d      display_registration;
    v_l      loyalty_registration;
    v_p      posm_registration;
begin
    if not p_approve and v_note is null then
        raise exception 'vui lòng nhập lý do từ chối';
    end if;

    if p_kind = 'display' then
        select * into v_d from display_registration where id = p_id for update;
        if v_d.id is null
           or not is_team_member(trade_registration_rep(v_d.salesperson_id, v_d.customer_id)) then
            raise exception 'không tìm thấy đăng ký trong nhóm của bạn';
        end if;
        if v_d.status <> 'pending' then
            raise exception 'đăng ký này đã được xử lý';
        end if;

        update display_registration
           set status = v_status, decision_note = v_note,
               decided_at = now(), decided_by = v_me
         where id = p_id;

        if not p_approve and v_d.salesperson_id is not null then
            update display_program_quota
               set used = greatest(used - coalesce(v_d.portion, 1), 0)
             where level_id = v_d.level_id and salesperson_id = v_d.salesperson_id;
        end if;

    elsif p_kind = 'loyalty' then
        select * into v_l from loyalty_registration where id = p_id for update;
        if v_l.id is null
           or not is_team_member(trade_registration_rep(v_l.salesperson_id, v_l.customer_id)) then
            raise exception 'không tìm thấy đăng ký trong nhóm của bạn';
        end if;
        if v_l.status <> 'pending' then
            raise exception 'đăng ký này đã được xử lý';
        end if;

        update loyalty_registration
           set status = v_status, decision_note = v_note,
               decided_at = now(), decided_by = v_me
         where id = p_id;

        if not p_approve and v_l.salesperson_id is not null then
            update loyalty_program_quota
               set used = greatest(used - v_l.portion, 0)
             where level_id = v_l.level_id and salesperson_id = v_l.salesperson_id;
        end if;

    elsif p_kind = 'posm' then
        select * into v_p from posm_registration where id = p_id for update;
        if v_p.id is null
           or not is_team_member(trade_registration_rep(v_p.salesperson_id, v_p.customer_id)) then
            raise exception 'không tìm thấy đăng ký trong nhóm của bạn';
        end if;
        if v_p.status <> 'pending' then
            raise exception 'đăng ký này đã được xử lý';
        end if;
        if p_approve and p_approved_qty is not null
           and (p_approved_qty < 1 or p_approved_qty > v_p.regis_qty) then
            raise exception 'số lượng duyệt phải từ 1 đến %', v_p.regis_qty;
        end if;

        update posm_registration
           set status = v_status, decision_note = v_note,
               decided_at = now(), decided_by = v_me,
               approved_qty = case when p_approve
                                   then coalesce(p_approved_qty, v_p.regis_qty)
                                   else 0 end
         where id = p_id;

    else
        raise exception 'loại đăng ký không hợp lệ: %', p_kind;
    end if;

    return jsonb_build_object('kind', p_kind, 'id', p_id, 'status', v_status);
end;
$$;

-- -----------------------------------------------------------------------------
-- Grants. Each function checks team membership itself; a rep calling one gets
-- an empty list or "not in your team", never someone else's rows.
-- -----------------------------------------------------------------------------

revoke execute on function mgr_approval_counts() from public;
revoke execute on function mgr_leave_list(text[], uuid) from public;
revoke execute on function mgr_decide_leave(uuid, boolean, text) from public;
revoke execute on function mgr_customer_list(text[], text) from public;
revoke execute on function mgr_customer_detail(uuid) from public;
revoke execute on function mgr_decide_customer(uuid, boolean, text, uuid) from public;
revoke execute on function mgr_trade_list(text[], text[]) from public;
revoke execute on function mgr_decide_trade(text, uuid, boolean, text, integer) from public;

grant execute on function mgr_approval_counts() to authenticated;
grant execute on function mgr_leave_list(text[], uuid) to authenticated;
grant execute on function mgr_decide_leave(uuid, boolean, text) to authenticated;
grant execute on function mgr_customer_list(text[], text) to authenticated;
grant execute on function mgr_customer_detail(uuid) to authenticated;
grant execute on function mgr_decide_customer(uuid, boolean, text, uuid) to authenticated;
grant execute on function mgr_trade_list(text[], text[]) to authenticated;
grant execute on function mgr_decide_trade(text, uuid, boolean, text, integer) to authenticated;
