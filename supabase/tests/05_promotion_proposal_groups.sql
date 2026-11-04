-- CTKM lựa chọn (OM10100ProposalGroup): rules sharing a proposal are one
-- choice. Uses the demo group NGK-T11: the NGK001 10-case gift and the 3 % on
-- soft drinks, both of which a basket of 20 cases of NGK001 qualifies for.

do $$
declare
    v_cust  uuid;
    v_gift  uuid;
    v_pct   uuid;
    v_lines jsonb;
    v       jsonb;
    v_site  uuid;
begin
    perform pg_temp.act_as_admin();
    select id into v_cust from customer where code = 'KH006';
    select s.id into v_gift from discount_sequence s
    join discount_proposal p on p.id = s.proposal_id
    where p.code = 'NGK-T11' and s.reward = 'free_item';
    select s.id into v_pct from discount_sequence s
    join discount_proposal p on p.id = s.proposal_id
    where p.code = 'NGK-T11' and s.reward = 'percent';
    perform pg_temp.check(v_gift is not null and v_pct is not null, 'demo group has a gift rule and a percentage rule');

    -- Gifts in stock, so the gift rule is not cut by the depot.
    select id into v_site from site where is_active order by code limit 1;
    update site_stock set qty_base = 1000000
    where site_id = v_site and product_id in (select product_id from discount_free_item where sequence_id = v_gift);

    v_lines := jsonb_build_array(jsonb_build_object(
        'product_id', (select id from product where code = 'NGK001'), 'uom_code', 'CASE', 'qty', 20));

    perform pg_temp.act_as('nvbh01');

    -- No pick: both are worked out, the percentage is taken (it outranks
    -- goods), and only it counts in the total.
    v := calculate_promotions(v_cust, v_lines);
    perform pg_temp.check(
        exists (select 1 from jsonb_array_elements(v -> 'earned') e where e ->> 'sequence_id' = v_gift::text)
        and exists (select 1 from jsonb_array_elements(v -> 'earned') e where e ->> 'sequence_id' = v_pct::text),
        'no pick: both alternatives are offered');
    perform pg_temp.check(
        (select bool_and((e ->> 'is_default')::boolean) from jsonb_array_elements(v -> 'earned') e
         where e ->> 'sequence_id' = v_pct::text)
        and not (select bool_or((e ->> 'is_default')::boolean) from jsonb_array_elements(v -> 'earned') e
                 where e ->> 'sequence_id' = v_gift::text),
        'no pick: the percentage is taken by default');
    perform pg_temp.check(
        (select e ->> 'proposal_name' from jsonb_array_elements(v -> 'earned') e
         where e ->> 'sequence_id' = v_gift::text limit 1) = 'Ưu đãi nước giải khát',
        'the group is named on its rules');
    perform pg_temp.check(
        (v ->> 'total_discount')::numeric = (
            select coalesce(sum((e ->> 'discount_amount')::numeric), 0)
            from jsonb_array_elements(v -> 'earned') e
            where coalesce((e ->> 'is_default')::boolean, true)),
        'no pick: the total counts only what is taken');

    -- The gift picked: the percentage is not worked out at all, and the gift
    -- is what the group gives.
    v := calculate_promotions(v_cust, v_lines, current_date, null, array[v_gift]);
    perform pg_temp.check(
        not exists (select 1 from jsonb_array_elements(v -> 'earned') e where e ->> 'sequence_id' = v_pct::text),
        'gift picked: the percentage is skipped');
    perform pg_temp.check(
        (select bool_and((e ->> 'is_default')::boolean) from jsonb_array_elements(v -> 'earned') e
         where e ->> 'sequence_id' = v_gift::text),
        'gift picked: the gift is taken');
end;
$$;

do $$ begin raise exception 'ALL PASSED'; end $$;
