-- Chỉ tiêu nhân viên: who may read and write a rep's targets, which months,
-- and that a save replaces the month. Relies on the seed's hierarchy:
-- amkv01 (AM) over gsbh01 (nvbh01, nvbh02) and gsbh02 (nvbh03).

do $$
declare
    v_sheet jsonb;
begin
    -- An SS reads their rep's month, but may not change it.
    perform pg_temp.act_as('gsbh01');
    v_sheet := mgr_targets(pg_temp.this_month(), pg_temp.sp('nvbh01'));
    perform pg_temp.check((v_sheet ->> 'can_edit')::boolean = false, 'SS reads targets with can_edit false');
    perform pg_temp.check(jsonb_array_length(v_sheet -> 'categories') > 0, 'every active product group is listed');
    perform pg_temp.check_refused(
        format('select mgr_targets_save(%L::jsonb)', jsonb_build_object(
            'salesperson_id', pg_temp.sp('nvbh01'), 'month', pg_temp.this_month(), 'revenue_target', 1)),
        'chỉ quản lý khu vực', 'SS may not save targets');

    -- Another SS's rep is out of reach, read or write.
    perform pg_temp.act_as('gsbh02');
    perform pg_temp.check_refused(
        format('select mgr_targets(%L, %L)', pg_temp.this_month(), pg_temp.sp('nvbh01')),
        'không thuộc nhóm', 'SS may not read another team''s rep');

    -- An AM edits the current month, but not one already over.
    perform pg_temp.act_as('amkv01');
    v_sheet := mgr_targets(pg_temp.this_month(), pg_temp.sp('nvbh01'));
    perform pg_temp.check((v_sheet ->> 'can_edit')::boolean, 'AM may edit the current month');
    v_sheet := mgr_targets((pg_temp.this_month() - interval '1 month')::date, pg_temp.sp('nvbh01'));
    perform pg_temp.check((v_sheet ->> 'can_edit')::boolean = false, 'nobody edits a past month');
    perform pg_temp.check_refused(
        format('select mgr_targets_save(%L::jsonb)', jsonb_build_object(
            'salesperson_id', pg_temp.sp('nvbh01'),
            'month', (pg_temp.this_month() - interval '1 month')::date, 'revenue_target', 1)),
        'tháng đã qua', 'saving a past month is refused');
    perform pg_temp.check_refused(
        format('select mgr_targets_save(%L::jsonb)', jsonb_build_object(
            'salesperson_id', pg_temp.sp('gsbh01'), 'month', pg_temp.this_month(), 'revenue_target', 1)),
        'không thuộc nhóm', 'targets are for reps, not supervisors');
    perform pg_temp.check_refused(
        format('select mgr_targets_save(%L::jsonb)', jsonb_build_object(
            'salesperson_id', pg_temp.sp('nvbh01'), 'month', pg_temp.this_month(), 'revenue_target', -5)),
        'không được âm', 'a negative target is refused');
end;
$$;

-- A save replaces the whole month: the group given a figure has it, the rest
-- have none, and no products are left over.
do $$
declare
    v_ngk   uuid := (select id from product_category where code = 'NGK');
    v_sheet jsonb;
begin
    perform pg_temp.act_as('amkv01');
    perform mgr_targets_save(jsonb_build_object(
        'salesperson_id', pg_temp.sp('nvbh01'),
        'month', pg_temp.this_month(),
        'revenue_target', 123000000,
        'order_target', 45,
        'categories', jsonb_build_array(jsonb_build_object('id', v_ngk, 'target', 5000000)),
        'products', '[]'::jsonb));

    v_sheet := mgr_targets(pg_temp.this_month(), pg_temp.sp('nvbh01'));
    perform pg_temp.check((v_sheet ->> 'revenue_target')::bigint = 123000000, 'revenue target saved');
    perform pg_temp.check((v_sheet ->> 'order_target')::int = 45, 'order target saved');
    perform pg_temp.check(
        (select (c ->> 'target')::bigint from jsonb_array_elements(v_sheet -> 'categories') c
          where (c ->> 'id')::uuid = v_ngk) = 5000000,
        'the group given a figure has it');
    perform pg_temp.check(
        (select count(*) from jsonb_array_elements(v_sheet -> 'categories') c
          where (c ->> 'id')::uuid <> v_ngk and c ->> 'target' is not null) = 0,
        'the other groups have none');
    perform pg_temp.check(
        (select count(*) from jsonb_array_elements(v_sheet -> 'products') p where p ->> 'target' is not null) = 0,
        'no product targets are left over');

    -- No overall figures removes the overall row rather than storing zeros.
    perform mgr_targets_save(jsonb_build_object(
        'salesperson_id', pg_temp.sp('nvbh01'), 'month', pg_temp.this_month(),
        'categories', '[]'::jsonb, 'products', '[]'::jsonb));
    v_sheet := mgr_targets(pg_temp.this_month(), pg_temp.sp('nvbh01'));
    perform pg_temp.check(v_sheet ->> 'revenue_target' is null, 'clearing the figures removes the row');
end;
$$;

-- Row-level security: a rep reads their own targets and nobody else's.
do $$
begin
    perform pg_temp.act_as('nvbh01');
    perform pg_temp.check((select count(*) from sales_target_product) > 0, 'a rep reads their own product targets');
    perform pg_temp.check(
        (select count(*) from sales_target_product where salesperson_id <> pg_temp.sp('nvbh01')) = 0,
        'a rep reads no one else''s product targets');
    perform pg_temp.act_as('nvbh03');
    perform pg_temp.check(
        (select count(*) from sales_target_product where salesperson_id = pg_temp.sp('nvbh01')) = 0,
        'another rep cannot read them');
end;
$$;

do $$ begin raise exception 'ALL PASSED'; end $$;
