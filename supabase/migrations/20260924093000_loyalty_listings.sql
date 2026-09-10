-- =============================================================================
-- Đang tích lũy đến đâu, và còn chương trình nào mở
--
--   loyalty_for                what the outlet is in, and how far it has got
--   loyalty_open_for           what it could still join, with this rep's slots
--   submit_loyalty_registration  InsertTradeRegis, TradeType 'A'
--
-- The progress figure is the reason this is worth having. `line_amount` is what
-- the outlet actually owed on the counted products — gross less the line's
-- discount plus its VAT — so the band is measured against the same money the
-- invoice shows. Cancelled orders do not count towards anything.
-- =============================================================================

create function loyalty_for(p_customer_id uuid)
returns table (
    program_id       uuid,
    program_code     text,
    program_name     text,
    specification    text,
    counts_by        loyalty_counts_by,
    from_date        date,
    to_date          date,
    level_id         uuid,
    level_code       text,
    level_name       text,
    target_from      numeric,
    target_to        numeric,
    reward_basis_points integer,
    status           text,
    registered_at    date,
    /** What the outlet has bought of the counted products in the window. */
    achieved         numeric,
    /** Left to reach the band this outlet signed up at. Zero once it is met. */
    remaining        numeric
)
language sql
stable
security invoker
set search_path = public, pg_temp
as $$
    with joined as (
        select
            p.id, p.code, p.name, p.specification, p.counts_by,
            p.from_date, p.to_date,
            lv.id as level_id, lv.code as level_code, lv.name as level_name,
            lv.target_from, lv.target_to, lv.reward_basis_points,
            r.status, r.registered_at
        from loyalty_registration r
        join loyalty_program p on p.id = r.program_id
        join loyalty_program_level lv on lv.id = r.level_id
        where r.customer_id = p_customer_id
          and r.status <> 'rejected'
          and p.is_active
    ),
    -- One pass over the orders per programme. The window is the programme's own,
    -- not the month: a programme that started mid-month counts from its own start.
    bought as (
        select
            j.id as program_id,
            coalesce(sum(
                case when j.counts_by = 'amount' then l.line_amount else l.base_qty end
            ), 0)::numeric as achieved
        from joined j
        left join sales_order o
            on o.customer_id = p_customer_id
           and o.status <> 'cancelled'
           and o.order_date between j.from_date and j.to_date
        left join sales_order_line l
            on l.order_id = o.id
           and exists (
               select 1 from loyalty_program_item it
               where it.program_id = j.id and it.product_id = l.product_id
           )
        group by j.id
    )
    select
        j.id, j.code, j.name, j.specification, j.counts_by,
        j.from_date, j.to_date,
        j.level_id, j.level_code, j.level_name,
        j.target_from, j.target_to, j.reward_basis_points,
        j.status, j.registered_at,
        b.achieved,
        greatest(j.target_from - b.achieved, 0)
    from joined j
    join bought b on b.program_id = j.id
    order by j.name;
$$;

comment on function loyalty_for(uuid) is
    'Loyalty programmes this outlet is in, with what it has bought of the counted products inside each window. The legacy returns Actual = 0 here and reads OM_AccumulatedPoints instead; this derives it from the orders.';

-- -----------------------------------------------------------------------------

create function loyalty_open_for(p_customer_id uuid)
returns table (
    program_id      uuid,
    program_code    text,
    program_name    text,
    specification   text,
    counts_by       loyalty_counts_by,
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
        from loyalty_program p, me
        where p.is_active
          and (p.branch_id is null or p.branch_id = me.branch_id)
          and not exists (
              select 1 from loyalty_registration r
              where r.program_id = p.id and r.customer_id = p_customer_id
          )
    )
    select
        p.id, p.code, p.name, p.specification, p.counts_by,
        p.from_date, p.to_date, p.opens, p.closes,
        coalesce((
            select jsonb_agg(jsonb_build_object(
                'level_id', lv.id,
                'level_code', lv.code,
                'level_name', lv.name,
                'target_from', lv.target_from,
                'target_to', lv.target_to,
                'reward_basis_points', lv.reward_basis_points,
                'slots_left', case
                    when q.id is null then null
                    else greatest(q.slots - q.used, 0)
                end
            ) order by lv.sort_order, lv.target_from)
            from loyalty_program_level lv
            left join loyalty_program_quota q
                on q.level_id = lv.id and q.salesperson_id = (select id from me)
            where lv.program_id = p.id
        ), '[]'::jsonb)
    from open_now p
    where current_date between p.opens and p.closes
    order by p.name;
$$;

comment on function loyalty_open_for(uuid) is
    'Loyalty programmes this outlet may still be signed up for today, with the slots this rep has left at each level. API_GetTradeProgramByCust, TradeType A.';

-- -----------------------------------------------------------------------------

create function submit_loyalty_registration(p_payload jsonb)
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
    v_quota    loyalty_program_quota%rowtype;
    v_id       uuid;
begin
    if v_visit_id is null or v_program is null or v_level is null then
        raise exception 'submit_loyalty_registration needs visit_id, program_id and level_id';
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

comment on function submit_loyalty_registration(jsonb) is
    'Signs an outlet up for a loyalty programme at a level, pending head office, spending one of the rep''s slots. InsertTradeRegis, TradeType A.';

revoke execute on function submit_loyalty_registration(jsonb) from public;
grant execute on function submit_loyalty_registration(jsonb) to authenticated;
