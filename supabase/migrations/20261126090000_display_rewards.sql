-- =============================================================================
-- Trả thưởng trưng bày trên đơn hàng (API_GetProgramReward)
--
-- A display programme pays the outlet once its result is in: the manager
-- approves what the audits showed, and the reward is then paid on the outlet's
-- next orders — money off, a percentage of the order, or goods — until it is
-- used up or the programme's reward window closes.
--
--   OM_TDisplayReward (Status 'C')  -> display_reward (status 'confirmed')
--   OM_OrdDisplay / OM_PDAOrdDisplay -> sales_order_display_reward
--   OM_TDisplay.ReturnFromDate/To    -> display_program.reward_from / reward_to
--   OM_TDisplayReward.ApplyType      -> display_program_level.reward_type
--                                       (A money, P percent, Q goods)
--
-- What is still due is the confirmed reward less what non-cancelled orders
-- have already paid of it, which is the legacy's union of the reward rows with
-- the order rows negated. A percentage is paid once, on the order it is taken
-- on; money and goods can be spread over several orders.
--
-- The result is decided in the manager app (Kết quả trưng bày): one reward per
-- approved registration, worth the registered level's reward times its
-- portion. The audits are shown beside it as the evidence; the decision is the
-- manager's, as the legacy's approval screen made it.
-- =============================================================================

alter table display_program
    add column reward_from date,
    add column reward_to   date;

comment on column display_program.reward_from is
    'First day the reward may be paid on an order (OM_TDisplay.ReturnFromDate). Null: from the result.';
comment on column display_program.reward_to is
    'Last day it may be paid (ReturnToDate). Null: no end.';

alter table display_program_level
    add column reward_type text not null default 'amount'
        check (reward_type in ('amount', 'percent', 'goods')),
    add column reward_percent numeric(7, 4) not null default 0,
    add column reward_product_id uuid references product (id),
    add column reward_uom_code text,
    add column reward_qty integer not null default 0 check (reward_qty >= 0);

comment on column display_program_level.reward_type is
    'What the level pays: bonus_amount in money, reward_percent of an order, or reward_qty of a product.';

create table display_reward (
    id              uuid primary key default gen_random_uuid(),
    registration_id uuid not null unique references display_registration (id) on delete cascade,
    program_id      uuid not null references display_program (id),
    level_id        uuid not null references display_program_level (id),
    customer_id     uuid not null references customer (id),
    reward_type     text not null check (reward_type in ('amount', 'percent', 'goods')),
    amount          bigint not null default 0 check (amount >= 0),
    percent         numeric(7, 4) not null default 0,
    product_id      uuid references product (id),
    uom_code        text,
    qty             integer not null default 0 check (qty >= 0),
    /** confirmed: payable; rejected: the result did not earn it. */
    status          text not null check (status in ('confirmed', 'rejected')),
    decision_note   text,
    decided_by      uuid references salesperson (id),
    decided_at      timestamptz not null default now()
);

create index on display_reward (customer_id) where status = 'confirmed';

alter table display_reward enable row level security;
create policy "everyone reads display rewards" on display_reward
    for select to authenticated using (true);

create table sales_order_display_reward (
    id         uuid primary key default gen_random_uuid(),
    order_id   uuid not null references sales_order (id) on delete cascade,
    reward_id  uuid not null references display_reward (id),
    amount     bigint not null default 0 check (amount >= 0),
    percent    numeric(7, 4) not null default 0,
    qty        integer not null default 0 check (qty >= 0),
    created_at timestamptz not null default now(),
    unique (order_id, reward_id)
);

alter table sales_order_display_reward enable row level security;
create policy "reads display rewards of orders it can see" on sales_order_display_reward
    for select to authenticated
    using (exists (select 1 from sales_order o where o.id = order_id));

-- Demo: TB2609 and TBQ0926 pay their rewards from the end of the programme
-- for a month.
update display_program set reward_from = to_date, reward_to = to_date + 30
where reward_from is null;

/**
 * What an outlet may still be paid on an order dated [p_date]: confirmed
 * rewards inside their programme's window, less what its non-cancelled orders
 * already took. Definer, because a rep's own RLS hides other reps' orders and
 * the outlet's balance is the same whoever sells to it.
 */
create or replace function display_rewards_due(p_customer_id uuid, p_date date default current_date)
returns table (
    reward_id    uuid,
    program_code text,
    program_name text,
    level_name   text,
    reward_type  text,
    amount_left  bigint,
    percent      numeric,
    product_id   uuid,
    product_code text,
    product_name text,
    uom_code     text,
    uom_name     text,
    qty_left     integer
)
language sql
stable
security definer
set search_path = public, pg_temp
as $$
    select r.id, p.code, p.name, l.name, r.reward_type,
           greatest(r.amount - coalesce(paid.amount, 0), 0)::bigint,
           case when r.reward_type = 'percent' then r.percent else 0 end,
           r.product_id, pr.code, pr.name, r.uom_code, u.name,
           greatest(r.qty - coalesce(paid.qty, 0), 0)::integer
    from display_reward r
    join display_program p on p.id = r.program_id
    join display_program_level l on l.id = r.level_id
    left join product pr on pr.id = r.product_id
    left join uom u on u.code = r.uom_code
    left join lateral (
        select sum(s.amount) as amount, sum(s.qty) as qty, count(*) as times
        from sales_order_display_reward s
        join sales_order o on o.id = s.order_id
        where s.reward_id = r.id and o.status <> 'cancelled'
    ) paid on true
    where r.customer_id = p_customer_id
      and r.status = 'confirmed'
      and (p.reward_from is null or p_date >= p.reward_from)
      and (p.reward_to is null or p_date <= p.reward_to)
      and case r.reward_type
              when 'amount' then r.amount > coalesce(paid.amount, 0)
              when 'goods' then r.qty > coalesce(paid.qty, 0)
              else coalesce(paid.times, 0) = 0
          end
    order by p.code, l.name;
$$;

/**
 * Kết quả trưng bày: the team's approved registrations with what their audits
 * showed, and whether the reward has been decided. [p_decided] false lists
 * those still waiting, true those already decided.
 */
create or replace function mgr_display_results(p_decided boolean default false)
returns jsonb
language sql
stable
security definer
set search_path = public, pg_temp
as $$
    select coalesce(jsonb_agg(row order by (row ->> 'program_code'), (row ->> 'customer_code')), '[]'::jsonb)
    from (
        select jsonb_build_object(
            'registration_id', g.id,
            'program_code', p.code,
            'program_name', p.name,
            'program_to', p.to_date,
            'level_name', l.name,
            'reward_type', l.reward_type,
            'reward_amount', l.bonus_amount * greatest(g.portion, 1),
            'reward_percent', l.reward_percent,
            'reward_qty', l.reward_qty * greatest(g.portion, 1),
            'reward_product', pr.name,
            'customer_code', c.code,
            'customer_name', c.name,
            'salesperson', staff_brief(trade_registration_rep(g.salesperson_id, g.customer_id)),
            'audits', a.audits,
            'achieved', a.achieved,
            'last_audit', a.last_audit,
            'decision', case when r.id is null then null else r.status end,
            'decision_note', r.decision_note
        ) as row
        from display_registration g
        join display_program p on p.id = g.program_id
        join display_program_level l on l.id = g.level_id
        join customer c on c.id = g.customer_id
        left join product pr on pr.id = l.reward_product_id
        left join display_reward r on r.registration_id = g.id
        left join lateral (
            select count(*) as audits,
                   count(*) filter (where da.achieved) as achieved,
                   max(da.audit_date) as last_audit
            from display_audit da
            where da.program_id = g.program_id and da.customer_id = g.customer_id
        ) a on true
        where g.status = 'approved'
          and trade_registration_rep(g.salesperson_id, g.customer_id) in (select team_salesperson_ids())
          and (r.id is not null) = p_decided
    ) rows;
$$;

/** The manager's decision on one result: the reward is confirmed, or refused. */
create or replace function mgr_decide_display_reward(
    p_registration_id uuid,
    p_approve         boolean,
    p_note            text default null
)
returns text
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
    v_reg  display_registration;
    v_lvl  display_program_level;
    v_note text := nullif(btrim(coalesce(p_note, '')), '');
begin
    select * into v_reg from display_registration where id = p_registration_id;
    if v_reg.id is null or v_reg.status <> 'approved'
       or not is_team_member(trade_registration_rep(v_reg.salesperson_id, v_reg.customer_id)) then
        raise exception 'không tìm thấy kết quả trưng bày trong nhóm của bạn';
    end if;
    if not p_approve and v_note is null then
        raise exception 'vui lòng nhập lý do từ chối';
    end if;
    if exists (select 1 from display_reward where registration_id = p_registration_id) then
        raise exception 'kết quả này đã được xử lý';
    end if;

    select * into v_lvl from display_program_level where id = v_reg.level_id;

    insert into display_reward (
        registration_id, program_id, level_id, customer_id, reward_type,
        amount, percent, product_id, uom_code, qty,
        status, decision_note, decided_by
    )
    values (
        v_reg.id, v_reg.program_id, v_reg.level_id, v_reg.customer_id, v_lvl.reward_type,
        case when v_lvl.reward_type = 'amount' then v_lvl.bonus_amount * greatest(v_reg.portion, 1) else 0 end,
        case when v_lvl.reward_type = 'percent' then v_lvl.reward_percent else 0 end,
        case when v_lvl.reward_type = 'goods' then v_lvl.reward_product_id end,
        case when v_lvl.reward_type = 'goods' then v_lvl.reward_uom_code end,
        case when v_lvl.reward_type = 'goods' then v_lvl.reward_qty * greatest(v_reg.portion, 1) else 0 end,
        case when p_approve then 'confirmed' else 'rejected' end,
        v_note,
        current_salesperson_id()
    );

    return case when p_approve then 'confirmed' else 'rejected' end;
end;
$$;

-- Whose a decided result is, for the push the rep gets (see approval_owner).
create or replace function approval_owner(p_kind text, p_id uuid)
returns uuid
language sql
stable
security definer
set search_path = public, pg_temp
as $$
    select case p_kind
        when 'leave' then (select salesperson_id from leave_request where id = p_id)
        when 'customer' then (select created_by_salesperson_id from customer where id = p_id)
        when 'display' then (select trade_registration_rep(salesperson_id, customer_id)
                               from display_registration where id = p_id)
        when 'loyalty' then (select trade_registration_rep(salesperson_id, customer_id)
                               from loyalty_registration where id = p_id)
        when 'posm' then (select trade_registration_rep(salesperson_id, customer_id)
                            from posm_registration where id = p_id)
        when 'display_result' then (select trade_registration_rep(salesperson_id, customer_id)
                                      from display_registration where id = p_id)
    end;
$$;

revoke execute on function display_rewards_due(uuid, date) from public, anon;
revoke execute on function mgr_display_results(boolean) from public, anon;
revoke execute on function mgr_decide_display_reward(uuid, boolean, text) from public, anon;
grant execute on function display_rewards_due(uuid, date) to authenticated;
grant execute on function mgr_display_results(boolean) to authenticated;
grant execute on function mgr_decide_display_reward(uuid, boolean, text) to authenticated;

CREATE OR REPLACE FUNCTION public.submit_order_core(p_order jsonb, p_seller uuid)
 RETURNS uuid
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public', 'pg_temp'
AS $function$
declare
    v_order_id   uuid := (p_order ->> 'id')::uuid;
    v_visit_id   uuid := (p_order ->> 'visit_id')::uuid;
    v_order_date date := coalesce((p_order ->> 'order_date')::date, current_date);
    -- Who the order is for (its owner, order number, route budget) and who is
    -- booking it. The same person on the rep's app; on a supervisor's
    -- work-with visit the rep they are riding with.
    v_sp_id      uuid := p_seller;
    v_caller     uuid := current_salesperson_id();
    v_sp_code    text;
    v_branch_id  uuid;
    v_customer   uuid;
    v_class_id   uuid;
    v_order_no   text;
    v_expected   integer;
    v_inserted   integer;
    v_promo      jsonb;
    v_cartons    numeric;   -- what the order sells, counted in cases
    v_min_cases  numeric;
    v_earned     jsonb;
    v_choice     jsonb;
    v_disc_id    uuid;
    v_take_amt   boolean;
    v_next_line  integer;
    v_free       jsonb;
    v_manual     jsonb;
    v_reward     jsonb;
    v_due        record;
    v_entry      record;
    v_amount     bigint;
    v_gross      bigint;
    v_budget     record;
    v_draw       numeric;
    v_ship_address uuid := nullif(p_order ->> 'ship_address_id', '')::uuid;
begin
    if v_order_id is null or v_visit_id is null then
        raise exception 'submit_order needs both id and visit_id';
    end if;

    select code, branch_id into v_sp_code, v_branch_id
    from salesperson where id = v_sp_id;

    -- The customer comes from the visit rather than the payload: an order must
    -- belong to the outlet the rep actually called on. This also fails closed
    -- on someone else's visit, since the rep cannot read it.
    select v.customer_id, c.class_id into v_customer, v_class_id
    from visit v
    join customer c on c.id = v.customer_id
    where v.id = v_visit_id and v.salesperson_id = v_caller
      and (v_caller = v_sp_id or v.work_with_salesperson_id = v_sp_id);

    if v_customer is null then
        raise exception 'visit % is not an open visit of this salesperson', v_visit_id;
    end if;

    -- An address that belongs to a different outlet would send the goods to
    -- the wrong shop, and RLS would not stop it: the rep can read every
    -- customer's addresses, so the tie to this order's customer is checked here.
    if v_ship_address is not null and not exists (
        select 1 from customer_ship_address a
        where a.id = v_ship_address and a.customer_id = v_customer and a.is_active
    ) then
        raise exception 'địa chỉ giao hàng không thuộc khách hàng này';
    end if;

    -- Derived from the order id, so a retry produces the same number instead of
    -- burning a sequence value per attempt.
    v_order_no := v_sp_code || '-' || to_char(v_order_date, 'YYYYMMDD') || '-' ||
                  upper(substr(replace(v_order_id::text, '-', ''), 1, 6));

    insert into sales_order (
        id, order_no, customer_id, salesperson_id, branch_id, visit_id,
        order_date, note, client_total_amount, client_created_at, ship_address_id
    )
    values (
        v_order_id, v_order_no, v_customer, v_sp_id, v_branch_id, v_visit_id,
        v_order_date,
        nullif(p_order ->> 'note', ''),
        coalesce((p_order ->> 'client_total_amount')::bigint, 0),
        coalesce((p_order ->> 'client_created_at')::timestamptz, now()),
        v_ship_address
    )
    on conflict (id) do nothing;

    if not found then
        -- Replayed from the outbox; the first delivery already booked it.
        return v_order_id;
    end if;

    if v_caller is distinct from v_sp_id then
        update sales_order set taken_by_salesperson_id = v_caller where id = v_order_id;
    end if;

    select count(*) into v_expected
    from jsonb_array_elements(p_order -> 'lines');

    if v_expected = 0 then
        raise exception 'order % has no lines', v_order_id;
    end if;

    with input as (
        select
            (l ->> 'line_no')::integer   as line_no,
            (l ->> 'product_id')::uuid   as product_id,
            (l ->> 'uom_code')::text     as uom_code,
            (l ->> 'qty')::integer       as qty
        from jsonb_array_elements(p_order -> 'lines') as l
    ),
    priced as (
        select
            i.line_no,
            i.product_id,
            i.uom_code,
            i.qty,
            pu.conversion_rate,
            pr.price,
            p.vat_basis_points,
            i.qty::bigint * pr.price as gross_amount
        from input i
        join product p on p.id = i.product_id and p.is_active
        join product_uom pu
            on pu.product_id = i.product_id and pu.uom_code = i.uom_code
        -- Prefer the customer's class over the list price. `nulls last` is what
        -- makes the fallback work, so it is load-bearing, not cosmetic.
        join lateral (
            select pl.price
            from price_list pl
            where pl.product_id = i.product_id
              and pl.uom_code = i.uom_code
              and (pl.class_id = v_class_id or pl.class_id is null)
              and v_order_date between pl.from_date and pl.to_date
            order by pl.class_id nulls last
            limit 1
        ) pr on true
    )
    insert into sales_order_line (
        order_id, line_no, product_id, uom_code, qty,
        conversion_rate, base_qty, price, vat_basis_points,
        gross_amount, vat_amount, line_amount
    )
    select
        v_order_id,
        line_no,
        product_id,
        uom_code,
        qty,
        conversion_rate,
        qty * conversion_rate,
        price,
        vat_basis_points,
        gross_amount,
        -- Half-up in integer arithmetic: + 5000 before dividing by 10000 is the
        -- rounding the client performs too, and both must agree to the dong.
        (gross_amount * vat_basis_points + 5000) / 10000,
        gross_amount + (gross_amount * vat_basis_points + 5000) / 10000
    from priced;

    get diagnostics v_inserted = row_count;

    -- An unpriced or discontinued product drops out of the joins above. Silently
    -- booking the rest would deliver an order the customer never agreed to.
    if v_inserted <> v_expected then
        raise exception
            'order %: % of % lines could not be priced for %',
            v_order_id, v_expected - v_inserted, v_expected, v_order_date;
    end if;

    -- -------------------------------------------------------------------------
    -- Đơn tối thiểu theo thùng (OM10100, BUG-2026-74458): what the order sells,
    -- counted in cases, must reach min_order_carton_qty. Gifts are not sales. A
    -- product with no case unit counts its base unit as the case, as the BA
    -- ruled.
    -- -------------------------------------------------------------------------
    select coalesce((
        select nullif(trim(value), '')::numeric
        from app_setting where key = 'min_order_carton_qty'
    ), 0.5)
    into v_min_cases;

    select coalesce(sum(
        l.qty * pu.conversion_rate::numeric / coalesce(nullif(pc.conversion_rate, 0), 1)
    ), 0)
    into v_cartons
    from sales_order_line l
    join product_uom pu on pu.product_id = l.product_id and pu.uom_code = l.uom_code
    left join product_uom pc on pc.product_id = l.product_id and pc.uom_code = 'CASE'
    where l.order_id = v_order_id and not l.is_free;

    if v_cartons < v_min_cases - 0.000000001 then
        raise exception 'đơn hàng phải đạt tối thiểu % thùng (hiện có % thùng)',
            trim(to_char(v_min_cases, 'FM999990.00')), trim(to_char(v_cartons, 'FM999990.00'));
    end if;

    -- -------------------------------------------------------------------------
    -- Automatic promotions, recomputed here from the lines just booked.
    -- -------------------------------------------------------------------------

    v_promo := calculate_promotions(
        v_customer,
        (select jsonb_agg(jsonb_build_object(
                    'product_id', product_id,
                    'uom_code', uom_code,
                    'qty', qty))
         from sales_order_line
         where order_id = v_order_id and not is_free),
        v_order_date,
        v_sp_id,
        -- CTKM lựa chọn: the rule the rep took in each group, if they chose.
        (select array_agg(value::uuid)
         from jsonb_array_elements_text(coalesce(p_order -> 'proposal_picks', '[]'::jsonb)))
    );

    select coalesce(max(line_no), 0) into v_next_line
    from sales_order_line where order_id = v_order_id;

    for v_earned in select * from jsonb_array_elements(v_promo -> 'earned')
    loop
        -- The alternatives of a "CTKM lựa chọn" group that were not taken.
        continue when not coalesce((v_earned ->> 'is_default')::boolean, true);

        -- What the rep chose for this rule, if they were offered anything.
        select e into v_choice
        from jsonb_array_elements(coalesce(p_order -> 'promotions', '[]'::jsonb)) as e
        where e ->> 'sequence_id' = v_earned ->> 'sequence_id'
        limit 1;

        -- `amount_or_item` defaults to the money, which is the branch that needs
        -- no further decision: taking goods means naming which goods.
        v_take_amt := case
            when v_earned ->> 'reward' <> 'amount_or_item' then null
            when v_choice is null then true
            else coalesce((v_choice ->> 'take_amount')::boolean, true)
        end;

        insert into sales_order_discount (
            order_id, sequence_id, break_id, portion,
            discount_amount, percent, descr
        )
        values (
            v_order_id,
            (v_earned ->> 'sequence_id')::uuid,
            (v_earned ->> 'break_id')::uuid,
            (v_earned ->> 'portion')::integer,
            case
                when v_earned ->> 'reward' = 'amount_or_item' and v_take_amt is false
                    then 0
                else (v_earned ->> 'discount_amount')::bigint
            end,
            (v_earned ->> 'percent')::numeric,
            coalesce(v_earned ->> 'sequence_name', '') ||
                case when coalesce(v_earned ->> 'break_name', '') <> ''
                     then ' - ' || (v_earned ->> 'break_name') else '' end
        )
        returning id into v_disc_id;

        -- Goods, when the rule gives them and the rep did not take the money.
        if v_earned ->> 'reward' = 'free_item'
           or (v_earned ->> 'reward' = 'amount_or_item' and v_take_amt is false) then

            for v_free in
                select * from jsonb_array_elements(v_earned -> 'free_items')
            loop
                -- A bundle gives everything on the list. A list of alternatives
                -- gives the one the rep named, or the pre-selected first.
                if v_choice ->> 'free_product_id' is not null then
                    if v_free ->> 'product_id' <> (v_choice ->> 'free_product_id')
                       or (v_choice ->> 'free_uom_code' is not null
                           and v_free ->> 'uom_code' <> (v_choice ->> 'free_uom_code')) then
                        continue;
                    end if;
                elsif not coalesce((v_free ->> 'chosen')::boolean, false) then
                    continue;
                end if;

                insert into sales_order_discount_free_item (
                    order_discount_id, product_id, uom_code, qty
                )
                values (
                    v_disc_id,
                    (v_free ->> 'product_id')::uuid,
                    (v_free ->> 'uom_code')::text,
                    (v_free ->> 'qty')::integer
                )
                on conflict (order_discount_id, product_id, uom_code) do update
                    set qty = sales_order_discount_free_item.qty + excluded.qty;

                v_next_line := v_next_line + 1;

                insert into sales_order_line (
                    order_id, line_no, product_id, uom_code, qty,
                    conversion_rate, base_qty, price, vat_basis_points,
                    gross_amount, discount_amount, vat_amount, line_amount, is_free
                )
                select
                    v_order_id,
                    v_next_line,
                    (v_free ->> 'product_id')::uuid,
                    (v_free ->> 'uom_code')::text,
                    (v_free ->> 'qty')::integer,
                    pu.conversion_rate,
                    (v_free ->> 'qty')::integer * pu.conversion_rate,
                    0, 0, 0, 0, 0, 0, true
                from product_uom pu
                where pu.product_id = (v_free ->> 'product_id')::uuid
                  and pu.uom_code = (v_free ->> 'uom_code')::text
                on conflict (order_id, product_id, uom_code, is_free) do update
                    set qty = sales_order_line.qty + excluded.qty,
                        base_qty = sales_order_line.base_qty + excluded.base_qty;
            end loop;
        end if;

        -- What this rule drew from its budget, if it has one. Recorded and
        -- deducted in the same breath: the engine has already refused anything
        -- the pot could not cover, so what is left here is the spending itself.
        --
        -- Booked per order rather than per level, because the pot is a limit on
        -- what a route or an outlet may be given and not on how the giving was
        -- arranged into levels.
        for v_budget in
            select
                s.budget_id,
                b.counts,
                a.id as allocation_id
            from discount_sequence s
            join discount_budget b on b.id = s.budget_id
            join discount_budget_allocation a
                on a.budget_id = b.id
               and a.target_id = case
                   when b.alloc_by = 'customer' then v_customer
                   else (
                       select r.id from sales_route r
                       where r.salesperson_id = v_sp_id and r.is_active
                       order by r.code limit 1
                   )
               end
            where s.id = (v_earned ->> 'sequence_id')::uuid
              and s.budget_id is not null
        loop
            v_draw := case v_budget.counts
                when 'lots' then (v_earned ->> 'portion')::numeric
                when 'free_item' then coalesce(
                    (
                        select sum((f ->> 'qty')::numeric)
                        from jsonb_array_elements(v_earned -> 'free_items') as f
                        where coalesce((f ->> 'chosen')::boolean, false)
                    ),
                    0
                )
                else (v_earned ->> 'discount_amount')::numeric
            end;

            if v_draw > 0 then
                insert into sales_order_budget_draw (
                    order_id, budget_id, allocation_id, sequence_id, amount
                )
                values (
                    v_order_id,
                    v_budget.budget_id,
                    v_budget.allocation_id,
                    (v_earned ->> 'sequence_id')::uuid,
                    v_draw
                )
                on conflict (order_id, sequence_id) do nothing;

                update discount_budget_allocation
                set spent = spent + v_draw
                where id = v_budget.allocation_id;
            end if;
        end loop;

    end loop;

    -- -------------------------------------------------------------------------
    -- Manual discounts. Named by the client, valued by the catalogue.
    -- -------------------------------------------------------------------------

    -- The gross the percentage entries are taken on: goods sold, before VAT and
    -- before any automatic discount. Automatic and manual both come off the same
    -- figure, so applying one never quietly shrinks the other.
    select coalesce(sum(gross_amount), 0) into v_gross
    from sales_order_line where order_id = v_order_id and not is_free;

    for v_manual in
        select * from jsonb_array_elements(coalesce(p_order -> 'manual_promotions', '[]'::jsonb))
    loop
        select m.* into v_entry
        from manual_promotions_for(v_customer) m
        where m.promotion_id = (v_manual ->> 'promotion_id')::uuid;

        -- Not in the catalogue this outlet may draw on today: expired, another
        -- branch's, or invented. Refused rather than skipped â€” a rep who was
        -- shown it and applied it deserves to be told it did not take.
        if v_entry is null then
            raise exception
                'order %: manual promotion % is not available for this outlet',
                v_order_id, v_manual ->> 'promotion_id';
        end if;

        if v_entry.promo_type = 'percent' then
            v_amount := round(v_gross * v_entry.value / 100);
        elsif v_entry.promo_type = 'amount' then
            v_amount := v_entry.value::bigint;

            -- An editable entry is a ceiling the rep may come under. Anything
            -- above it is refused, not clamped: a rep who typed 500.000 and got
            -- 50.000 would find out from the customer.
            if v_manual ? 'amount' then
                if not v_entry.allow_edit then
                    raise exception
                        'order %: manual promotion % is not editable',
                        v_order_id, v_entry.code;
                end if;

                if (v_manual ->> 'amount')::bigint > v_entry.value then
                    raise exception
                        'order %: manual promotion % allows at most %, % asked for',
                        v_order_id, v_entry.code, v_entry.value,
                        v_manual ->> 'amount';
                end if;

                v_amount := greatest((v_manual ->> 'amount')::bigint, 0);
            end if;
        else
            v_amount := 0;
        end if;

        insert into sales_order_manual_discount (
            order_id, promotion_id, promo_type, discount_amount, percent, descr
        )
        values (
            v_order_id,
            v_entry.promotion_id,
            v_entry.promo_type,
            v_amount,
            case when v_entry.promo_type = 'percent' then v_entry.value end,
            v_entry.name
        )
        returning id into v_disc_id;

        -- Goods. Everything the entry lists, because a manual promotion has no
        -- alternatives â€” the rep already chose at the level above.
        if v_entry.promo_type = 'free_item' then
            for v_free in select * from jsonb_array_elements(v_entry.items)
            loop
                insert into sales_order_manual_discount_item (
                    manual_discount_id, product_id, uom_code, qty
                )
                values (
                    v_disc_id,
                    (v_free ->> 'product_id')::uuid,
                    (v_free ->> 'uom_code')::text,
                    (v_free ->> 'qty')::integer
                );

                v_next_line := v_next_line + 1;

                insert into sales_order_line (
                    order_id, line_no, product_id, uom_code, qty,
                    conversion_rate, base_qty, price, vat_basis_points,
                    gross_amount, discount_amount, vat_amount, line_amount, is_free
                )
                select
                    v_order_id,
                    v_next_line,
                    (v_free ->> 'product_id')::uuid,
                    (v_free ->> 'uom_code')::text,
                    (v_free ->> 'qty')::integer,
                    pu.conversion_rate,
                    (v_free ->> 'qty')::integer * pu.conversion_rate,
                    0, 0, 0, 0, 0, 0, true
                from product_uom pu
                where pu.product_id = (v_free ->> 'product_id')::uuid
                  and pu.uom_code = (v_free ->> 'uom_code')::text
                on conflict (order_id, product_id, uom_code, is_free) do update
                    set qty = sales_order_line.qty + excluded.qty,
                        base_qty = sales_order_line.base_qty + excluded.base_qty;
            end loop;
        end if;
    end loop;

    -- -------------------------------------------------------------------------
    -- Trả thưởng trưng bày (API_GetProgramReward): the display rewards the rep
    -- pays on this order. Named by the client, valued here from what is still
    -- due: the whole remaining money, the percentage of the goods sold, or the
    -- remaining goods as free lines.
    -- -------------------------------------------------------------------------
    for v_reward in
        select * from jsonb_array_elements(coalesce(p_order -> 'display_rewards', '[]'::jsonb))
    loop
        select d.* into v_due
        from display_rewards_due(v_customer, v_order_date) d
        where d.reward_id = (v_reward ->> 'reward_id')::uuid;

        if v_due.reward_id is null then
            raise exception 'phần thưởng trưng bày không còn để trả cho khách hàng này';
        end if;

        insert into sales_order_display_reward (order_id, reward_id, amount, percent, qty)
        values (
            v_order_id,
            v_due.reward_id,
            case v_due.reward_type
                when 'amount' then v_due.amount_left
                when 'percent' then round(v_gross * v_due.percent / 100)::bigint
                else 0
            end,
            v_due.percent,
            case when v_due.reward_type = 'goods' then v_due.qty_left else 0 end
        );

        if v_due.reward_type = 'goods' and v_due.qty_left > 0 then
            v_next_line := v_next_line + 1;

            insert into sales_order_line (
                order_id, line_no, product_id, uom_code, qty,
                conversion_rate, base_qty, price, vat_basis_points,
                gross_amount, discount_amount, vat_amount, line_amount, is_free
            )
            select
                v_order_id, v_next_line, v_due.product_id, v_due.uom_code, v_due.qty_left,
                pu.conversion_rate, v_due.qty_left * pu.conversion_rate,
                0, 0, 0, 0, 0, 0, true
            from product_uom pu
            where pu.product_id = v_due.product_id and pu.uom_code = v_due.uom_code
            on conflict (order_id, product_id, uom_code, is_free) do update
                set qty = sales_order_line.qty + excluded.qty,
                    base_qty = sales_order_line.base_qty + excluded.base_qty;
        end if;
    end loop;

    -- -------------------------------------------------------------------------
    -- Totals. Free lines contribute nothing; both kinds of discount come off the
    -- total the customer pays, never off the gross, so both figures stay the
    -- ones an invoice prints.
    -- -------------------------------------------------------------------------

    update sales_order o
    set sub_total       = t.sub_total,
        vat_amount      = t.vat_amount,
        discount_amount = coalesce(d.auto, 0) + coalesce(d.manual, 0) + coalesce(d.display, 0),
        total_amount    = greatest(
            t.total_amount - coalesce(d.auto, 0) - coalesce(d.manual, 0) - coalesce(d.display, 0), 0
        )
    from (
        select
            coalesce(sum(gross_amount), 0) as sub_total,
            coalesce(sum(vat_amount), 0)   as vat_amount,
            coalesce(sum(line_amount), 0)  as total_amount
        from sales_order_line
        where order_id = v_order_id and not is_free
    ) t
    left join lateral (
        select
            (
                select coalesce(sum(discount_amount), 0)
                from sales_order_discount where order_id = v_order_id
            ) as auto,
            (
                select coalesce(sum(discount_amount), 0)
                from sales_order_manual_discount where order_id = v_order_id
            ) as manual,
            (
                select coalesce(sum(amount), 0)
                from sales_order_display_reward where order_id = v_order_id
            ) as display
    ) d on true
    where o.id = v_order_id;

    -- Same transaction as the order: the step is done because the order exists.
    insert into visit_step_result (visit_id, form_id, completed_at, payload)
    values (
        v_visit_id,
        'take_order',
        now(),
        jsonb_build_object('order_id', v_order_id, 'order_no', v_order_no)
    )
    on conflict (visit_id, form_id) do update
        set completed_at = excluded.completed_at,
            payload      = excluded.payload;

    return v_order_id;
end;
$function$
;
