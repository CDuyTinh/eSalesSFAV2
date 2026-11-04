-- The default gift of an "or" gift (BA 2026-10-13): one the depot can cover,
-- then one the order sells, then the most stock in cases, then the campaign's
-- order. Uses the seed's BK003 rule ("Mua 10 gói", money or one of BK001 /
-- BK002, BK001 listed first).

-- Stock for one product at the depot, whether or not it had a row.
create function pg_temp.stock(p_site uuid, p_product uuid, p_qty integer) returns void language sql as $$
    insert into site_stock (site_id, product_id, qty_base) values (p_site, p_product, p_qty)
    on conflict (site_id, product_id) do update set qty_base = excluded.qty_base
$$;

create function pg_temp.chosen_gift(p_cust uuid, p_lines jsonb) returns text language sql as $$
    select p.code
    from jsonb_array_elements(calculate_promotions(p_cust, p_lines) -> 'earned') e,
         jsonb_array_elements(e -> 'free_items') f
    join product p on p.id = (f ->> 'product_id')::uuid
    where e ->> 'reward' = 'amount_or_item' and (f ->> 'chosen')::boolean
    limit 1
$$;
grant execute on function pg_temp.chosen_gift(uuid, jsonb) to authenticated;

do $$
declare
    v_cust  uuid;
    v_site  uuid;
    v_bk001 uuid;
    v_bk002 uuid;
    v_buy   jsonb;
begin
    perform pg_temp.act_as_admin();
    select id into v_cust from customer where code = 'KH006';
    select id into v_site from site where is_active order by code limit 1;
    select id into v_bk001 from product where code = 'BK001';
    select id into v_bk002 from product where code = 'BK002';
    v_buy := jsonb_build_object('product_id', (select id from product where code = 'BK003'), 'uom_code', 'PACK', 'qty', 10);

    -- The seed rule ran out in October and names its audience; for this test
    -- it runs today and for everyone (rolled back with the rest).
    update discount_sequence set to_date = current_date + 30
    where reward = 'amount_or_item' and is_active;
    delete from discount_audience
    where sequence_id in (select id from discount_sequence where reward = 'amount_or_item' and is_active);

    -- BK001 out of stock: the one the depot can cover is the default.
    perform pg_temp.stock(v_site, v_bk001, 0);
    perform pg_temp.stock(v_site, v_bk002, 1000);
    perform pg_temp.act_as('nvbh01');
    perform pg_temp.check(pg_temp.chosen_gift(v_cust, jsonb_build_array(v_buy)) = 'BK002',
        'a gift the depot cannot cover is not the default');

    -- Both in stock, BK001 with more of it: the most stock wins.
    perform pg_temp.act_as_admin();
    perform pg_temp.stock(v_site, v_bk001, 100000);
    perform pg_temp.stock(v_site, v_bk002, 100);
    perform pg_temp.act_as('nvbh01');
    perform pg_temp.check(pg_temp.chosen_gift(v_cust, jsonb_build_array(v_buy)) = 'BK001',
        'with both in stock, the one with more stock is the default');

    -- The order sells BK002 too: a gift the order sells comes before stock.
    perform pg_temp.check(pg_temp.chosen_gift(v_cust, jsonb_build_array(v_buy,
            jsonb_build_object('product_id', v_bk002, 'uom_code', 'PCS', 'qty', 1))) = 'BK002',
        'a gift the order itself sells is the default');
end;
$$;

do $$ begin raise exception 'ALL PASSED'; end $$;
