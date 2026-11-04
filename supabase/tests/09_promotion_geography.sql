-- Khuyến mãi theo địa lý and the promotions warehouse. Uses the seed's order
-- rule KM-DOC (from 5 million) and KM-CASE (gift NGK002), for KH001 of BR01.

create function pg_temp.has_rule(p_code text) returns boolean language sql as $$
    select exists (
        select 1
        from jsonb_array_elements(calculate_promotions(
            (select id from customer where code = 'KH001'),
            jsonb_build_array(jsonb_build_object(
                'product_id', (select id from product where code = 'NGK001'), 'uom_code', 'CASE', 'qty', 25))
        ) -> 'earned') e
        join discount_sequence s on s.id = (e ->> 'sequence_id')::uuid
        join discount_program p on p.id = s.program_id
        where p.code = p_code
    )
$$;
grant execute on function pg_temp.has_rule(text) to authenticated;

do $$
declare
    v_seq    uuid;
    v_cust   customer;
    v_region uuid;
    v_other  uuid;
    v_prov   uuid;
    v_promo  uuid;
    v_main   uuid;
    v_gift   uuid;
    v_avail  integer;
begin
    perform pg_temp.act_as_admin();
    select s.id into v_seq from discount_sequence s join discount_program p on p.id = s.program_id where p.code = 'KM-DOC';
    select * into v_cust from customer where code = 'KH001';
    select ar.region_id into v_region from branch b join area ar on ar.id = b.area_id where b.id = v_cust.branch_id;
    select id into v_other from region where id <> v_region limit 1;
    select d.province_id into v_prov from ward w join district d on d.id = w.district_id where w.id = v_cust.ward_id;
    perform pg_temp.check(v_seq is not null and v_region is not null and v_other is not null, 'seed has KM-DOC and two regions');

    perform pg_temp.act_as('nvbh01');
    perform pg_temp.check(pg_temp.has_rule('KM-DOC'), 'with no audience the rule applies');

    -- Another region only: not for this outlet.
    perform pg_temp.act_as_admin();
    insert into discount_audience (sequence_id, kind, target_id) values (v_seq, 'region', v_other);
    perform pg_temp.act_as('nvbh01');
    perform pg_temp.check(not pg_temp.has_rule('KM-DOC'), 'a rule for another region does not apply');

    -- Its own region too: applies.
    perform pg_temp.act_as_admin();
    insert into discount_audience (sequence_id, kind, target_id) values (v_seq, 'region', v_region);
    perform pg_temp.act_as('nvbh01');
    perform pg_temp.check(pg_temp.has_rule('KM-DOC'), 'a rule for the outlet''s region applies');

    -- Right place, wrong channel: both tests must pass.
    perform pg_temp.act_as_admin();
    insert into discount_audience (sequence_id, kind, target_id)
    select v_seq, 'channel', id from sales_channel where id is distinct from v_cust.channel_id limit 1;
    perform pg_temp.act_as('nvbh01');
    perform pg_temp.check(not pg_temp.has_rule('KM-DOC'), 'the right region with another channel does not apply');

    -- By province alone.
    if v_prov is not null then
        perform pg_temp.act_as_admin();
        delete from discount_audience where sequence_id = v_seq;
        insert into discount_audience (sequence_id, kind, target_id) values (v_seq, 'province', v_prov);
        perform pg_temp.act_as('nvbh01');
        perform pg_temp.check(pg_temp.has_rule('KM-DOC'), 'a rule for the outlet''s province applies');
    end if;

    -- The promotions warehouse: gifts are counted from it.
    perform pg_temp.act_as_admin();
    select id into v_gift from product where code = 'NGK002';
    select id into v_main from site where branch_id = v_cust.branch_id and is_active order by code limit 1;
    select id into v_promo from site where branch_id = v_cust.branch_id and is_active and id <> v_main order by code limit 1;
    if v_promo is not null then
        update site set site_type = 'promo' where id = v_promo;
        insert into site_stock (site_id, product_id, qty_base) values (v_promo, v_gift, 48)
        on conflict (site_id, product_id) do update set qty_base = excluded.qty_base;
        insert into site_stock (site_id, product_id, qty_base) values (v_main, v_gift, 2400)
        on conflict (site_id, product_id) do update set qty_base = excluded.qty_base;
        perform pg_temp.act_as('nvbh01');
        select (f ->> 'available_qty')::int into v_avail
        from jsonb_array_elements(calculate_promotions(v_cust.id, jsonb_build_array(jsonb_build_object(
                 'product_id', (select id from product where code = 'NGK001'), 'uom_code', 'CASE', 'qty', 20))) -> 'earned') e,
             jsonb_array_elements(e -> 'free_items') f
        where (f ->> 'product_id')::uuid = v_gift
        limit 1;
        perform pg_temp.check(v_avail = 2, 'gifts are counted from the promotions warehouse (48 cans = 2 cases)');
    end if;
end;
$$;

do $$ begin raise exception 'ALL PASSED'; end $$;
