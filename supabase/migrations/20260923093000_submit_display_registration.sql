-- =============================================================================
-- Chương trình mở cho cửa hàng, và việc đăng ký
--
--   display_programs_open_for    API_GetTradeProgramByCust, TradeType 'D'
--   submit_display_registration  InsertTradeRegis, TradeType 'D'
--
-- The listing deliberately shows levels the rep has no slots left for rather
-- than hiding them. "Mức 3 — hết suất" is information; a level that silently
-- vanishes is a rep ringing head office to ask where it went.
-- =============================================================================

create function display_programs_open_for(p_customer_id uuid)
returns table (
    program_id      uuid,
    program_code    text,
    program_name    text,
    specification   text,
    from_date       date,
    to_date         date,
    regis_from_date date,
    regis_to_date   date,
    levels          jsonb
)
language sql
stable
security invoker
set search_path = public, pg_temp
as $$
    with me as (
        select id, branch_id from salesperson where id = current_salesperson_id()
    ),
    open_now as (
        select
            p.*,
            coalesce(p.regis_from_date, p.from_date) as opens,
            coalesce(p.regis_to_date, p.to_date) as closes
        from display_program p, me
        where p.is_active
          and p.requires_registration
          and (p.branch_id is null or p.branch_id = me.branch_id)
          -- Not already in it. The legacy's NOT IN (OM_TDisplayCustomer) — a
          -- second signup is refused rather than shown and then refused.
          and not exists (
              select 1 from display_registration r
              where r.program_id = p.id and r.customer_id = p_customer_id
          )
    )
    select
        p.id, p.code, p.name, p.specification, p.from_date, p.to_date,
        p.opens, p.closes,
        coalesce((
            select jsonb_agg(jsonb_build_object(
                'level_id', lv.id,
                'level_code', lv.code,
                'level_name', lv.name,
                'required_faces', lv.required_faces,
                'bonus_amount', lv.bonus_amount,
                -- Null quota means head office set none for this rep, which the
                -- legacy treats as no ceiling rather than as none allowed.
                'slots_left', case
                    when q.id is null then null
                    else greatest(q.slots - q.used, 0)
                end
            ) order by lv.sort_order, lv.required_faces)
            from display_program_level lv
            left join display_program_quota q
                on q.level_id = lv.id and q.salesperson_id = (select id from me)
            where lv.program_id = p.id
        ), '[]'::jsonb)
    from open_now p
    where current_date between p.opens and p.closes
    order by p.name;
$$;

comment on function display_programs_open_for(uuid) is
    'Display programmes this outlet may still be signed up for today, with the slots this rep has left at each level. API_GetTradeProgramByCust, TradeType D.';

-- -----------------------------------------------------------------------------

create function submit_display_registration(p_payload jsonb)
returns uuid
language plpgsql
security invoker
set search_path = public, pg_temp
as $$
declare
    v_visit_id uuid    := (p_payload ->> 'visit_id')::uuid;
    v_program  uuid    := (p_payload ->> 'program_id')::uuid;
    v_level    uuid    := (p_payload ->> 'level_id')::uuid;
    v_portion  integer := coalesce((p_payload ->> 'portion')::integer, 1);
    v_sp_id    uuid    := current_salesperson_id();
    v_branch   uuid;
    v_customer uuid;
    v_opens    date;
    v_closes   date;
    v_quota    display_program_quota%rowtype;
    v_id       uuid;
begin
    if v_visit_id is null or v_program is null or v_level is null then
        raise exception 'submit_display_registration needs visit_id, program_id and level_id';
    end if;

    if v_portion < 1 then
        raise exception 'a registration consumes at least one slot';
    end if;

    select v.customer_id into v_customer
    from visit v
    where v.id = v_visit_id and v.salesperson_id = v_sp_id;

    if v_customer is null then
        raise exception 'visit % is not a visit of this salesperson', v_visit_id;
    end if;

    select branch_id into v_branch from salesperson where id = v_sp_id;

    select coalesce(p.regis_from_date, p.from_date), coalesce(p.regis_to_date, p.to_date)
    into v_opens, v_closes
    from display_program p
    where p.id = v_program
      and p.is_active
      and p.requires_registration
      and (p.branch_id is null or p.branch_id = v_branch);

    if not found then
        raise exception 'this display programme does not take registrations here';
    end if;

    if current_date < v_opens or current_date > v_closes then
        raise exception
            'registration for this programme runs from % to %', v_opens, v_closes;
    end if;

    if not exists (
        select 1 from display_program_level lv
        where lv.id = v_level and lv.program_id = v_program
    ) then
        raise exception 'that level is not part of this programme';
    end if;

    -- One registration per outlet per programme, as the unique index says and as
    -- InsertTradeRegis refuses. Changing level is head office's to do.
    if exists (
        select 1 from display_registration r
        where r.program_id = v_program and r.customer_id = v_customer
    ) then
        raise exception 'this outlet is already registered for this programme';
    end if;

    -- The rep's own allocation for this level. No row means head office set no
    -- ceiling, which the legacy treats as unlimited rather than as nothing.
    select * into v_quota
    from display_program_quota
    where level_id = v_level and salesperson_id = v_sp_id
    for update;

    if found and v_quota.slots - v_quota.used < v_portion then
        raise exception
            'only % slot(s) left at this level', greatest(v_quota.slots - v_quota.used, 0);
    end if;

    insert into display_registration (
        program_id, level_id, customer_id, status, registered_at,
        salesperson_id, visit_id, portion
    )
    values (
        v_program, v_level, v_customer, 'pending', current_date,
        v_sp_id, v_visit_id, v_portion
    )
    returning id into v_id;

    if v_quota.id is not null then
        update display_program_quota
        set used = used + v_portion
        where id = v_quota.id;
    end if;

    return v_id;
end;
$$;

comment on function submit_display_registration(jsonb) is
    'Signs an outlet up for a display programme at a level, pending head office, spending one of the rep''s slots. InsertTradeRegis, TradeType D.';

revoke execute on function submit_display_registration(jsonb) from public;
grant execute on function submit_display_registration(jsonb) to authenticated;
