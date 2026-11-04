-- Số suất tối đa theo mức (OM10100LevelCap): a level pays at most what the
-- outlet has left of its lots and what the depot's gifts can cover, and what a
-- cut level did not use falls to the level below. Uses the seed's line rule S1
-- on NGK001 (cases): "Mua 10 thùng" above "Mua 5 thùng" (at most 3 lots).

do $$
declare
    v_cust    uuid;
    v_seq     uuid;
    v_b10     uuid;
    v_b5      uuid;
    v_lines   jsonb;
    v         jsonb;
    v_used    integer;
    v_gift    record;
    v_sold    numeric;
    v_site    uuid;
begin
    perform pg_temp.act_as_admin();

    select id into v_cust from customer where code = 'KH006';
    select s.id into v_seq
    from discount_sequence s
    join discount_condition_item ci on ci.sequence_id = s.id
    join product p on p.id = ci.product_id
    where p.code = 'NGK001' and ci.uom_code = 'CASE' and s.reward = 'free_item' and s.is_active
    limit 1;
    select id into v_b10 from discount_break where sequence_id = v_seq and break_qty = 10;
    select id into v_b5 from discount_break where sequence_id = v_seq and break_qty = 5;
    perform pg_temp.check(v_seq is not null and v_b10 is not null and v_b5 is not null, 'seed rule S1 found');

    v_lines := jsonb_build_array(jsonb_build_object(
        'product_id', (select id from product where code = 'NGK001'), 'uom_code', 'CASE', 'qty', 20));

    -- What KH006's orders already hold at the 10-case level.
    select coalesce(sum(d.portion), 0) into v_used
    from sales_order_discount d join sales_order o on o.id = d.order_id
    where d.break_id = v_b10 and o.customer_id = v_cust and o.status <> 'cancelled';

    -- Plenty of every gift, so stock does not get in the way of the quota tests.
    select id into v_site from site where is_active order by code limit 1;
    update site_stock set qty_base = 1000000
    where site_id = v_site
      and product_id in (select product_id from discount_free_item where sequence_id = v_seq);

    -- No cap: 20 cases earn the 10-case level twice.
    perform pg_temp.act_as('nvbh01');
    v := calculate_promotions(v_cust, v_lines);
    perform pg_temp.check(
        (select (e ->> 'portion')::int from jsonb_array_elements(v -> 'earned') e where e ->> 'break_id' = v_b10::text) = 2,
        'uncapped: two lots of the 10-case level');
    perform pg_temp.check(jsonb_array_length(v -> 'level_caps') = 0, 'uncapped: nothing reported as cut');

    -- One lot left for this outlet: one is paid, the other ten cases fall to
    -- the 5-case level (two lots), and the cut is reported as out of quota.
    perform pg_temp.act_as_admin();
    update discount_break set customer_max_lot = v_used + 1 where id = v_b10;
    perform pg_temp.act_as('nvbh01');
    v := calculate_promotions(v_cust, v_lines);
    perform pg_temp.check(
        (select (e ->> 'portion')::int from jsonb_array_elements(v -> 'earned') e where e ->> 'break_id' = v_b10::text) = 1,
        'quota 1: one lot of the 10-case level');
    perform pg_temp.check(
        (select (e ->> 'portion')::int from jsonb_array_elements(v -> 'earned') e where e ->> 'break_id' = v_b5::text) = 2,
        'quota 1: the other ten cases earn the 5-case level twice');
    perform pg_temp.check(
        (select (c ->> 'requested_lots')::int = 2 and (c ->> 'granted_lots')::int = 1
                and (c ->> 'out_of_quota')::boolean and not (c ->> 'out_of_stock')::boolean
         from jsonb_array_elements(v -> 'level_caps') c where c ->> 'break_id' = v_b10::text),
        'quota 1: cut reported, out of quota');

    -- Quota spent: the 10-case level pays nothing and the 5-case level takes
    -- the basket, up to its own three-lot ceiling.
    perform pg_temp.act_as_admin();
    update discount_break set customer_max_lot = v_used where id = v_b10;
    perform pg_temp.act_as('nvbh01');
    v := calculate_promotions(v_cust, v_lines);
    perform pg_temp.check(
        not exists (select 1 from jsonb_array_elements(v -> 'earned') e where e ->> 'break_id' = v_b10::text),
        'quota 0: the 10-case level is not paid');
    perform pg_temp.check(
        (select (e ->> 'portion')::int from jsonb_array_elements(v -> 'earned') e where e ->> 'break_id' = v_b5::text) = 3,
        'quota 0: the 5-case level takes the basket up to its max of 3');
    perform pg_temp.check(
        (select (c ->> 'granted_lots')::int = 0 from jsonb_array_elements(v -> 'level_caps') c
         where c ->> 'break_id' = v_b10::text),
        'quota 0: the cut level is reported with nothing granted');

    -- Gifts for one lot only: the 10-case level pays one, and the 5-case level,
    -- which gives the same gifts, finds the depot empty.
    perform pg_temp.act_as_admin();
    update discount_break set customer_max_lot = null where id = v_b10;
    for v_gift in
        select fi.product_id, fi.free_qty * pu.conversion_rate as per_lot
        from discount_free_item fi
        join product_uom pu on pu.product_id = fi.product_id and pu.uom_code = fi.uom_code
        where fi.sequence_id = v_seq
    loop
        -- What this order sells of the same product is not available to give.
        select coalesce(sum(20 * pu.conversion_rate), 0) into v_sold
        from product_uom pu join product p on p.id = pu.product_id
        where p.code = 'NGK001' and pu.uom_code = 'CASE' and pu.product_id = v_gift.product_id;
        update site_stock set qty_base = v_sold + v_gift.per_lot
        where site_id = v_site and product_id = v_gift.product_id;
    end loop;
    perform pg_temp.act_as('nvbh01');
    v := calculate_promotions(v_cust, v_lines);
    perform pg_temp.check(
        (select (e ->> 'portion')::int from jsonb_array_elements(v -> 'earned') e where e ->> 'break_id' = v_b10::text) = 1,
        'stock for one lot: one lot of the 10-case level');
    perform pg_temp.check(
        (select (c ->> 'out_of_stock')::boolean and (c ->> 'stock_lots')::int = 1
         from jsonb_array_elements(v -> 'level_caps') c where c ->> 'break_id' = v_b10::text),
        'stock for one lot: cut reported, out of stock');
    perform pg_temp.check(
        not exists (select 1 from jsonb_array_elements(v -> 'earned') e where e ->> 'break_id' = v_b5::text),
        'stock for one lot: nothing left for the 5-case level');
end;
$$;

do $$ begin raise exception 'ALL PASSED'; end $$;
