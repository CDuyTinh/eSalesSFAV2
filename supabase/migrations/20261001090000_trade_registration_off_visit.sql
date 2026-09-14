-- =============================================================================
-- Registering an outlet for a programme without standing in it
--
-- Both submit functions take a visit and derive the customer from it. That was
-- right while the only way in was the customer hub during a call, and it did
-- real work: deriving the customer from a visit the rep owns is what made it
-- impossible to register somebody else's outlet.
--
-- Đăng ký chương trình (TRADE_REGIS) is the other way in — a screen of its own,
-- reached from the Khác sheet, where a rep signs up any of their outlets
-- without walking into a visit first. That is how the legacy works:
-- InsertTradeRegis takes a CustId and no visit at all.
--
-- So the payload now takes either. A visit still derives the customer, because
-- that is the stronger statement; a bare customer_id is checked against the
-- rep's own routes instead, which is the same question the visit was answering
-- indirectly. Registering an outlet nobody assigned to you is still refused,
-- and now it is refused by name.
-- =============================================================================

/**
 * The customer a registration is for, however the caller named it.
 *
 * Raises rather than returning null: every refusal here is something the rep
 * needs told, and a null would surface later as a confusing not-null violation.
 */
create function registration_customer(
    p_visit_id    uuid,
    p_customer_id uuid
)
returns uuid
language plpgsql
stable
security invoker
set search_path = public, pg_temp
as $$
declare
    v_sp_id    uuid := current_salesperson_id();
    v_customer uuid;
begin
    if p_visit_id is not null then
        select v.customer_id into v_customer
        from visit v
        where v.id = p_visit_id and v.salesperson_id = v_sp_id;

        if v_customer is null then
            raise exception 'visit % is not a visit of this salesperson', p_visit_id;
        end if;

        return v_customer;
    end if;

    if p_customer_id is null then
        raise exception 'a registration needs either visit_id or customer_id';
    end if;

    -- Off-visit, the rep's routes are what say the outlet is theirs to sign up.
    if not exists (
        select 1
        from route_customer rc
        join sales_route r on r.id = rc.sales_route_id and r.is_active
        where rc.customer_id = p_customer_id
          and rc.is_active
          and r.salesperson_id = v_sp_id
    ) then
        raise exception 'khách hàng này không thuộc tuyến của bạn';
    end if;

    return p_customer_id;
end;
$$;

comment on function registration_customer(uuid, uuid) is
    'The outlet a registration is for: from the visit when there is one, otherwise from the rep''s routes.';

-- -----------------------------------------------------------------------------

create or replace function submit_display_registration(p_payload jsonb)
returns uuid
language plpgsql
security invoker
set search_path = public, pg_temp
as $$
declare
    v_visit_id uuid    := nullif(p_payload ->> 'visit_id', '')::uuid;
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
    if v_program is null or v_level is null then
        raise exception 'submit_display_registration needs program_id and level_id';
    end if;

    if v_portion < 1 then
        raise exception 'a registration consumes at least one slot';
    end if;

    v_customer := registration_customer(
        v_visit_id,
        nullif(p_payload ->> 'customer_id', '')::uuid
    );

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

-- -----------------------------------------------------------------------------

create or replace function submit_loyalty_registration(p_payload jsonb)
returns uuid
language plpgsql
security invoker
set search_path = public, pg_temp
as $$
declare
    v_visit_id uuid    := nullif(p_payload ->> 'visit_id', '')::uuid;
    v_program  uuid    := (p_payload ->> 'program_id')::uuid;
    v_level    uuid    := (p_payload ->> 'level_id')::uuid;
    v_portion  integer := coalesce((p_payload ->> 'portion')::integer, 1);
    v_sp_id    uuid    := current_salesperson_id();
    v_branch   uuid;
    v_customer uuid;
    v_opens    date;
    v_closes   date;
    v_quota    loyalty_program_quota%rowtype;
    v_id       uuid;
begin
    if v_program is null or v_level is null then
        raise exception 'submit_loyalty_registration needs program_id and level_id';
    end if;

    if v_portion < 1 then
        raise exception 'a registration consumes at least one slot';
    end if;

    v_customer := registration_customer(
        v_visit_id,
        nullif(p_payload ->> 'customer_id', '')::uuid
    );

    select branch_id into v_branch from salesperson where id = v_sp_id;

    select coalesce(p.regis_from_date, p.from_date), coalesce(p.regis_to_date, p.to_date)
    into v_opens, v_closes
    from loyalty_program p
    where p.id = v_program
      and p.is_active
      and (p.branch_id is null or p.branch_id = v_branch);

    if not found then
        raise exception 'this loyalty programme does not take registrations here';
    end if;

    if current_date < v_opens or current_date > v_closes then
        raise exception
            'registration for this programme runs from % to %', v_opens, v_closes;
    end if;

    if not exists (
        select 1 from loyalty_program_level lv
        where lv.id = v_level and lv.program_id = v_program
    ) then
        raise exception 'that level is not part of this programme';
    end if;

    if exists (
        select 1 from loyalty_registration r
        where r.program_id = v_program and r.customer_id = v_customer
    ) then
        raise exception 'this outlet is already registered for this programme';
    end if;

    select * into v_quota
    from loyalty_program_quota
    where level_id = v_level and salesperson_id = v_sp_id
    for update;

    if found and v_quota.slots - v_quota.used < v_portion then
        raise exception
            'only % slot(s) left at this level', greatest(v_quota.slots - v_quota.used, 0);
    end if;

    insert into loyalty_registration (
        program_id, level_id, customer_id, status, registered_at,
        salesperson_id, visit_id, portion, reason
    )
    values (
        v_program, v_level, v_customer, 'pending', current_date,
        v_sp_id, v_visit_id, v_portion, nullif(btrim(p_payload ->> 'reason'), '')
    )
    returning id into v_id;

    if v_quota.id is not null then
        update loyalty_program_quota
        set used = used + v_portion
        where id = v_quota.id;
    end if;

    return v_id;
end;
$$;
