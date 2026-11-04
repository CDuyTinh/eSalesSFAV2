-- =============================================================================
-- Chương trình khuyến mãi (API_GetTradePromotion) and the results badge
--
-- THE CATALOGUE. The legacy's Sản phẩm trọng tâm opens on "Chương trình KM":
-- every promotion running for the rep's distributor, and for each one what it
-- asks for (the products and levels) and what it gives (money, a percentage or
-- goods), with the budget still left. A rep reads it before the round to know
-- what to offer; the order screen then applies the same rules. Here it is a
-- screen of its own under Chuẩn bị.
--
-- Running means what the engine considers running: active, inside its dates,
-- for the rep's branch or every branch. Who it targets (channel, class, one
-- outlet) is shown as a note rather than filtered on, since the catalogue is
-- read without an outlet in mind.
--
-- THE BADGE. Kết quả trưng bày counts its waiting results in the approvals
-- menu like every other entry: approved registrations of the team whose
-- reward has not been decided.
-- =============================================================================

create or replace function mgr_approval_counts()
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
                     and trade_registration_rep(p.salesperson_id, p.customer_id) in (select id from team)),
        'work_plan', (select count(*) from work_plan w
                   where w.status = 'submitted'
                     and w.salesperson_id in (select id from team)),
        'display_result', (select count(*) from display_registration d
                   where d.status = 'approved'
                     and not exists (select 1 from display_reward r where r.registration_id = d.id)
                     and trade_registration_rep(d.salesperson_id, d.customer_id) in (select id from team))
    );
$$;

insert into menu_item (code, parent_code, title_key, sort_order)
values ('TRADE_PROMOTION', 'PREPARATION', 'menu_trade_promotion', 3)
on conflict (code) do nothing;

insert into translation (lang_code, key, value)
values ('vi', 'menu_trade_promotion', 'Chương trình khuyến mãi')
on conflict (lang_code, key) do update set value = excluded.value;

/**
 * Every promotion running on [p_date] for the caller's branch, with its levels,
 * the products it counts, what it gives and what its budget has left.
 */
create or replace function promotion_catalogue(p_date date default current_date)
returns jsonb
language sql
stable
set search_path = public, pg_temp
as $$
    with me as (
        select s.id, s.branch_id from salesperson s where s.id = current_salesperson_id()
    )
    select coalesce(jsonb_agg(row order by row ->> 'program_code', row ->> 'code'), '[]'::jsonb)
    from (
        select jsonb_build_object(
            'sequence_id', s.id,
            'program_code', p.code,
            'program_name', p.name,
            'code', s.code,
            'name', s.name,
            'scope', p.scope,
            'break_by', s.break_by,
            'reward', s.reward,
            'from_date', s.from_date,
            'to_date', case when s.open_ended then null else s.to_date end,
            'is_bundle', s.is_bundle,
            'proposal_name', pr.name,
            'targeted', exists (select 1 from discount_audience a where a.sequence_id = s.id),
            'items', coalesce((
                select jsonb_agg(jsonb_build_object(
                    'product_code', pd.code,
                    'product_name', pd.name,
                    'uom_name', u.name,
                    'required_value', ci.required_value,
                    'bundle_qty', ci.bundle_qty
                ) order by pd.code)
                from discount_condition_item ci
                join product pd on pd.id = ci.product_id
                join uom u on u.code = ci.uom_code
                where ci.sequence_id = s.id
            ), '[]'::jsonb),
            'levels', coalesce((
                select jsonb_agg(jsonb_build_object(
                    'name', b.name,
                    'break_qty', b.break_qty,
                    'break_amt', b.break_amt,
                    'disc_amt', b.disc_amt,
                    'max_lot', b.max_lot,
                    'customer_max_lot', b.customer_max_lot
                ) order by b.break_qty, b.break_amt)
                from discount_break b
                where b.sequence_id = s.id
            ), '[]'::jsonb),
            'gifts', coalesce((
                select jsonb_agg(jsonb_build_object(
                    'product_name', pd.name,
                    'uom_name', u.name,
                    'qty', fi.free_qty
                ) order by fi.sort_order, pd.name)
                from discount_free_item fi
                join product pd on pd.id = fi.product_id
                join uom u on u.code = fi.uom_code
                where fi.sequence_id = s.id
            ), '[]'::jsonb),
            'budget', case when s.budget_id is null then null else jsonb_build_object(
                'name', bg.name,
                'counts', bg.counts,
                'allocated', ba.allocated,
                'spent', ba.spent,
                'remaining', ba.allocated - ba.spent
            ) end
        ) as row
        from discount_sequence s
        join discount_program p on p.id = s.program_id
        cross join me
        left join discount_proposal pr on pr.id = s.proposal_id
        left join discount_budget bg on bg.id = s.budget_id
        left join lateral (
            -- The rep's own route share; an outlet-level budget has no single
            -- figure to show here, so it shows none.
            select a.allocated, a.spent
            from discount_budget_allocation a
            where a.budget_id = s.budget_id
              and bg.alloc_by = 'route'
              and a.target_id = (
                  select r.id from sales_route r
                  where r.salesperson_id = me.id and r.is_active
                  order by r.code limit 1
              )
        ) ba on true
        where s.is_active
          and p.is_active
          and s.from_date <= p_date
          and (s.open_ended or p_date <= s.to_date)
          and (s.branch_id is null or s.branch_id = me.branch_id)
    ) rows;
$$;

revoke execute on function promotion_catalogue(date) from public, anon;
grant execute on function promotion_catalogue(date) to authenticated;
