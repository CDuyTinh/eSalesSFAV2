-- Trả thưởng trưng bày: a manager decides an approved registration's result,
-- the reward becomes due to the outlet, and what orders pay of it is taken off.
-- Uses the seed's TB2609 (reward window: its end date plus a month) and amkv01,
-- whose team covers every seed rep.

do $$
declare
    v_reg    display_registration;
    v_level  display_program_level;
    v_list   jsonb;
    v_due    record;
    v_order  uuid;
    v_reward uuid;
    v_rep    text;
begin
    perform pg_temp.act_as_admin();
    select g.* into v_reg
    from display_registration g
    join display_program p on p.id = g.program_id
    where g.status = 'approved' and p.code = 'TB2609'
      and not exists (select 1 from display_reward r where r.registration_id = g.id)
    order by g.created_at
    limit 1;
    perform pg_temp.check(v_reg.id is not null, 'seed has an approved TB2609 registration');
    select * into v_level from display_program_level where id = v_reg.level_id;
    -- Paid in money, so the balance arithmetic below is in money too.
    update display_program_level set reward_type = 'amount' where id = v_level.id;
    select code into v_rep from salesperson
    where id = trade_registration_rep(v_reg.salesperson_id, v_reg.customer_id);

    -- Waiting for a decision, and only the team sees it.
    perform pg_temp.act_as('amkv01');
    v_list := mgr_display_results(false);
    perform pg_temp.check(
        exists (select 1 from jsonb_array_elements(v_list) x where x ->> 'registration_id' = v_reg.id::text),
        'the result waits for a decision');
    perform pg_temp.check_refused(
        format('select mgr_decide_display_reward(%L, false, null)', v_reg.id),
        'lý do từ chối', 'a refusal needs a reason');

    -- Confirmed: due to the outlet at the level's money times the portion.
    perform pg_temp.check(mgr_decide_display_reward(v_reg.id, true) = 'confirmed', 'the reward is confirmed');
    perform pg_temp.check_refused(
        format('select mgr_decide_display_reward(%L, true)', v_reg.id),
        'đã được xử lý', 'a result is decided once');
    perform pg_temp.check(
        not exists (select 1 from jsonb_array_elements(mgr_display_results(false)) x
                    where x ->> 'registration_id' = v_reg.id::text)
        and exists (select 1 from jsonb_array_elements(mgr_display_results(true)) x
                    where x ->> 'registration_id' = v_reg.id::text and x ->> 'decision' = 'confirmed'),
        'it moves from waiting to decided');

    perform pg_temp.act_as(v_rep);
    select d.* into v_due from display_rewards_due(v_reg.customer_id) d
    join display_reward r on r.id = d.reward_id where r.registration_id = v_reg.id;
    perform pg_temp.check(v_due.amount_left = v_level.bonus_amount * greatest(v_reg.portion, 1),
        'the outlet is due the level''s money times its portion');
    v_reward := v_due.reward_id;

    -- An order pays part of it: the rest is still due. Cancelled, it pays nothing.
    perform pg_temp.act_as_admin();
    select id into v_order from sales_order where customer_id = v_reg.customer_id and status <> 'cancelled' limit 1;
    if v_order is not null then
        insert into sales_order_display_reward (order_id, reward_id, amount) values (v_order, v_reward, 100000);
        select d.* into v_due from display_rewards_due(v_reg.customer_id) d where d.reward_id = v_reward;
        perform pg_temp.check(v_due.amount_left = v_level.bonus_amount * greatest(v_reg.portion, 1) - 100000,
            'what an order paid is taken off');
        update sales_order set status = 'cancelled' where id = v_order;
        select d.* into v_due from display_rewards_due(v_reg.customer_id) d where d.reward_id = v_reward;
        perform pg_temp.check(v_due.amount_left = v_level.bonus_amount * greatest(v_reg.portion, 1),
            'a cancelled order gives it back');
    end if;

    -- Outside the window it is not due.
    update display_program set reward_to = current_date - 1 where id = v_reg.program_id;
    perform pg_temp.check(
        not exists (select 1 from display_rewards_due(v_reg.customer_id) d where d.reward_id = v_reward),
        'not due once the reward window has closed');
end;
$$;

do $$ begin raise exception 'ALL PASSED'; end $$;
