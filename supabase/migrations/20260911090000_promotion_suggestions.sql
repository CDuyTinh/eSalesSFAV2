-- =============================================================================
-- "Mua thêm 2 thùng nữa được tặng 1"
--
-- The legacy has a popup that fires while the rep is still choosing products,
-- naming what one more case would earn. This is the same thing without the
-- popup: the answer travels with the promotion preview the order screen already
-- refreshes on every basket change, so it costs no extra round trip.
--
-- It matters more than it looks. Promotions shown only on the confirmation page
-- arrive after the rep has finished deciding, which makes them a receipt. Shown
-- while the basket is open they are an argument, and the customer is still in
-- the room to hear it.
--
-- Deliberately not the same code path as `calculate_promotions`. That function
-- consumes as it goes, because that is what earning a level means; a suggestion
-- is a question about the basket as it stands, so it reads and never eats. Two
-- readings of one rule set, kept apart on purpose.
-- =============================================================================

create or replace function promotion_suggestions(
    p_customer_id uuid,
    p_lines       jsonb,
    p_order_date  date default current_date
)
-- Not stable: it fills a temp table, which a read-only transaction refuses.
returns jsonb
language plpgsql
security invoker
set search_path = public, pg_temp
as $$
declare
    v_out       jsonb := '[]'::jsonb;
    v_order_amt numeric;
begin
    -- The basket in every unit, once. A suggestion never mutates it, so unlike
    -- the engine this needs no working copy.
    create temporary table if not exists _sugg_cart (
        product_id uuid,
        uom_code   text,
        qty        numeric,
        amount     numeric
    );

    delete from _sugg_cart where true;

    insert into _sugg_cart (product_id, uom_code, qty, amount)
    select product_id, uom_code, qty, amount
    from cart_in_all_units(p_customer_id, p_order_date, p_lines);

    select coalesce(sum(amount), 0) into v_order_amt
    from (
        select (l ->> 'qty')::numeric * pr.price as amount
        from jsonb_array_elements(coalesce(p_lines, '[]'::jsonb)) as l
        join customer c on c.id = p_customer_id
        left join lateral (
            select pl.price
            from price_list pl
            where pl.product_id = (l ->> 'product_id')::uuid
              and pl.uom_code = (l ->> 'uom_code')::text
              and (pl.class_id = c.class_id or pl.class_id is null)
              and p_order_date between pl.from_date and pl.to_date
            order by pl.class_id nulls last
            limit 1
        ) pr on true
    ) t;

    with seq as (
        select * from applicable_discount_sequences(p_customer_id, p_order_date)
    ),
    -- What each rule is currently measured at, and against which product where
    -- the rule is a per-product one. A line rule is suggested against its
    -- closest condition item rather than all of them: "two more cases of Coca"
    -- is advice, "two more of any of these eleven things" is not.
    measured as (
        select
            s.sequence_id, s.program_name, s.sequence_name, s.scope, s.break_by,
            s.reward,
            case
                when s.scope = 'order' then v_order_amt
                when s.scope = 'group' then (
                    select coalesce(sum(
                        case when s.break_by = 'qty' then c.qty else c.amount end
                    ), 0)
                    from discount_condition_item ci
                    join _sugg_cart c
                        on c.product_id = ci.product_id and c.uom_code = ci.uom_code
                    where ci.sequence_id = s.sequence_id
                )
                else coalesce(best.measure, 0)
            end as measure,
            best.product_id,
            best.product_name,
            best.uom_name
        from seq s
        left join lateral (
            -- The condition item the basket is nearest to qualifying on. Nearest
            -- by what is already there, which is the one the rep can most easily
            -- top up.
            select
                ci.product_id,
                p.name as product_name,
                -- The unit as the rep reads it. A suggestion saying "3 CASE" in
                -- an app that everywhere else says "Thung" reads as a different
                -- unit, not the same one shouted.
                u.name as uom_name,
                case when s.break_by = 'qty' then c.qty else c.amount end as measure
            from discount_condition_item ci
            join product p on p.id = ci.product_id
            join uom u on u.code = ci.uom_code
            left join _sugg_cart c
                on c.product_id = ci.product_id and c.uom_code = ci.uom_code
            where ci.sequence_id = s.sequence_id
              and s.scope = 'line'
            order by coalesce(
                case when s.break_by = 'qty' then c.qty else c.amount end, 0
            ) desc
            limit 1
        ) best on true
    ),
    -- The cheapest level still out of reach. Strictly above what the basket
    -- measures, so a rule already earning its top level suggests nothing.
    next_step as (
        select
            m.*,
            b.id as break_id,
            b.name as break_name,
            case when m.break_by = 'qty' then b.break_qty else b.break_amt end as threshold
        from measured m
        join lateral (
            select b.*
            from discount_break b
            where b.sequence_id = m.sequence_id
              and case when m.break_by = 'qty' then b.break_qty else b.break_amt end
                  > m.measure
            order by case when m.break_by = 'qty' then b.break_qty else b.break_amt end
            limit 1
        ) b on true
    )
    select coalesce(
        jsonb_agg(
            jsonb_build_object(
                'sequence_id', n.sequence_id,
                'program_name', n.program_name,
                'sequence_name', n.sequence_name,
                'break_id', n.break_id,
                'break_name', n.break_name,
                'scope', n.scope,
                'break_by', n.break_by,
                'reward', n.reward,
                -- How much more. One of these is meaningful, per break_by.
                'needed_qty', case when n.break_by = 'qty'
                    then ceil(n.threshold - n.measure) else 0 end,
                'needed_amount', case when n.break_by = 'amount'
                    then ceil(n.threshold - n.measure) else 0 end,
                'product_id', n.product_id,
                'product_name', n.product_name,
                'uom_code', n.uom_name,
                -- What clearing it is worth, so the rep can say why it is worth
                -- doing rather than only what to do.
                'reward_amount', coalesce(
                    (select b.disc_amt from discount_break b where b.id = n.break_id), 0
                ),
                'reward_items', coalesce(
                    (
                        select jsonb_agg(jsonb_build_object(
                            'product_name', p.name,
                            'uom_code', fu.name,
                            'qty', fi.free_qty
                        ) order by fi.sort_order, p.name)
                        from discount_free_item fi
                        join product p on p.id = fi.product_id
                        join uom fu on fu.code = fi.uom_code
                        where fi.sequence_id = n.sequence_id
                    ),
                    '[]'::jsonb
                )
            )
            -- Nearest first: a rep reads two of these, not nine, and the near
            -- ones are the ones a customer says yes to.
            order by (n.threshold - n.measure)
        ),
        '[]'::jsonb
    )
    into v_out
    from next_step n
    -- An empty basket has nothing to be near to, and a suggestion to start
    -- shopping is not advice.
    where n.measure > 0;

    return v_out;
end;
$$;

revoke execute on function promotion_suggestions(uuid, jsonb, date) from public;
grant execute on function promotion_suggestions(uuid, jsonb, date) to authenticated;

comment on function promotion_suggestions(uuid, jsonb, date) is
    'What one more case would earn: the nearest unreached level of every rule '
    'this outlet qualifies for. The legacy popup_promotion, without the popup.';
