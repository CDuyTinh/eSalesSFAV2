-- =============================================================================
-- Dữ liệu mẫu: một tháng làm việc của nhóm gsbh01
--
-- The dashboard had nothing to show: the last order in the database is from
-- September, nvbh02 had no route at all, and nobody had a target. This seeds
-- the current month up to yesterday so Tổng quan has a shape to it:
--   * a route for nvbh02 over four outlets nvbh01 also covers;
--   * a monthly target for both reps;
--   * for every planned stop up to yesterday, a visit — most of them ending in
--     an order of one to three lines, priced the way the real lines are.
-- nvbh02 is seeded to sell noticeably less than nvbh01, so the ranking says
-- something.
--
-- Deterministic (hashtext of rep/customer/day) rather than random, so running
-- the numbers by hand gives the same answer twice.
-- =============================================================================

do $$
declare
    v_branch  uuid := '00000000-0000-0000-0000-000000000010';
    v_nam     uuid := '00000000-0000-0000-0000-000000000081';  -- nvbh01
    v_hoa     uuid := '00000000-0000-0000-0000-000000000082';  -- nvbh02
    v_route   uuid;
    v_month   date := date_trunc('month', current_date)::date;
    v_stop    record;
    v_visit   uuid;
    v_order   uuid;
    v_h       integer;
    v_lines   integer;
    v_i       integer;
    v_qty     integer;
    v_prod    record;
    v_sub     bigint;
    v_vat     bigint;
    v_seq     integer := 0;
begin
    -- nvbh02's route: two Mon/Wed/Fri outlets and two Tue/Thu/Sat ones.
    insert into sales_route (code, name, branch_id, salesperson_id)
    values ('R02', 'Tuyến Lê Thị Hoa', v_branch, v_hoa)
    on conflict (code) do nothing
    returning id into v_route;

    if v_route is not null then
        insert into route_customer (sales_route_id, customer_id, visit_order, visit_weekdays)
        select v_route, c.id, row_number() over (order by c.code),
               case when c.code in ('KH002', 'KH003') then '{1,3,5}'::smallint[]
                    else '{2,4,6}'::smallint[] end
        from customer c
        where c.code in ('KH002', 'KH003', 'KH006', 'KH007') and c.branch_id = v_branch
        on conflict (sales_route_id, customer_id) do nothing;
    end if;

    insert into sales_target (salesperson_id, period_month, revenue_target, order_target) values
        (v_nam, v_month, 140000000, 100),
        (v_hoa, v_month, 90000000, 60)
    on conflict (salesperson_id, period_month) do nothing;

    for v_stop in
        select r.salesperson_id, rc.customer_id, d::date as day, s.code as rep_code
        from generate_series(v_month, current_date - 1, interval '1 day') d
        join sales_route r on r.is_active and r.salesperson_id in (v_nam, v_hoa)
        join salesperson s on s.id = r.salesperson_id
        join route_customer rc on rc.sales_route_id = r.id and rc.is_active
        where extract(isodow from d)::smallint = any (rc.visit_weekdays)
          -- Leave days that already have real visits alone.
          and not exists (
              select 1 from visit v
              where v.salesperson_id = r.salesperson_id and v.customer_id = rc.customer_id
                and v.visit_date = d::date
          )
        order by d, r.salesperson_id, rc.visit_order
    loop
        v_h := abs(hashtext(v_stop.salesperson_id::text || v_stop.customer_id::text || v_stop.day::text));

        -- About one stop in ten is missed: no visit at all.
        continue when v_h % 10 = 0;

        insert into visit (customer_id, salesperson_id, branch_id, visit_date, status,
                           check_in_at, check_out_at)
        values (v_stop.customer_id, v_stop.salesperson_id, v_branch, v_stop.day,
                case when v_h % 7 = 0 then 'no_order' else 'completed' end::visit_status,
                (v_stop.day + time '08:00' + (v_h % 300) * interval '1 minute') at time zone 'Asia/Ho_Chi_Minh',
                (v_stop.day + time '08:20' + (v_h % 300) * interval '1 minute') at time zone 'Asia/Ho_Chi_Minh')
        returning id into v_visit;

        continue when v_h % 7 = 0;
        -- nvbh02 closes fewer of the visits it makes.
        continue when v_stop.salesperson_id = v_hoa and v_h % 3 = 0;

        v_seq := v_seq + 1;
        insert into sales_order (id, order_no, customer_id, salesperson_id, branch_id, visit_id,
                                 order_date, client_created_at, status)
        values (gen_random_uuid(), v_stop.rep_code || '-' || to_char(v_stop.day, 'YYYYMMDD') || '-S' || lpad(v_seq::text, 4, '0'),
                v_stop.customer_id, v_stop.salesperson_id, v_branch, v_visit, v_stop.day,
                (v_stop.day + time '08:15') at time zone 'Asia/Ho_Chi_Minh', 'new')
        returning id into v_order;

        v_lines := 1 + v_h % 3;
        v_sub := 0;
        v_vat := 0;
        v_i := 0;
        for v_prod in
            select * from (values
                ('00000000-0000-0000-0000-000000000c01'::uuid, 'CASE', 24, 222000::bigint, 1000),
                ('00000000-0000-0000-0000-000000000c05'::uuid, 'CASE', 24, 300000::bigint, 1000),
                ('00000000-0000-0000-0000-000000000c03'::uuid, 'CASE', 24, 112000::bigint, 800)
            ) as p(product_id, uom_code, rate, price, vat_bp)
            limit v_lines
        loop
            v_i := v_i + 1;
            v_qty := 1 + (v_h / (v_i * 7)) % 4;
            insert into sales_order_line (order_id, line_no, product_id, uom_code, qty,
                                          conversion_rate, base_qty, price, vat_basis_points,
                                          gross_amount, vat_amount, line_amount)
            values (v_order, v_i, v_prod.product_id, v_prod.uom_code, v_qty,
                    v_prod.rate, v_qty * v_prod.rate, v_prod.price, v_prod.vat_bp,
                    v_prod.price * v_qty,
                    v_prod.price * v_qty * v_prod.vat_bp / 10000,
                    v_prod.price * v_qty + v_prod.price * v_qty * v_prod.vat_bp / 10000);
            v_sub := v_sub + v_prod.price * v_qty;
            v_vat := v_vat + v_prod.price * v_qty * v_prod.vat_bp / 10000;
        end loop;

        update sales_order
           set sub_total = v_sub, vat_amount = v_vat, discount_amount = 0,
               total_amount = v_sub + v_vat, client_total_amount = v_sub + v_vat
         where id = v_order;
    end loop;
end;
$$;
