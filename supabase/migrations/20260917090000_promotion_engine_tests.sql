-- =============================================================================
-- Test cho engine khuyến mãi
--
-- Every behaviour in `calculate_promotions` was verified by hand, once, in a
-- transaction that was then rolled back. None of it was repeatable, which meant
-- the next person to touch the engine — including me — had nothing to catch a
-- regression. Two bugs in this feature made it past a hand check already: the
-- `IsDeductQtyAmt` reading that had the flag backwards, and a `min()` over
-- required items that let a rule fire without one of them. Both were found by
-- writing the next thing, not by testing the last one.
--
-- So this is the same set of checks, as a function that can be run again.
--
--     select * from test_calculate_promotions();
--
-- It is safe to run against the live database, and that is deliberate rather
-- than incidental. It has to deactivate the real campaigns while it runs — the
-- engine answers for every rule an outlet qualifies for, so a test with its own
-- fixtures would otherwise be reading the demo catalogue's answers too — and it
-- puts every one of them back before it returns, fixtures deleted, in the same
-- exception-safe block. Running it inside `begin; … rollback;` is still the
-- better habit; not doing so is no longer a disaster.
--
-- What it does not do is test the client. `PromotionSummaryTest` covers the
-- half the device owns — which gift was chosen, what a manual entry is worth —
-- and those two suites meet at the payload rather than overlapping.
-- =============================================================================

create or replace function test_calculate_promotions()
returns table (
    case_name text,
    passed    boolean,
    expected  text,
    actual    text
)
-- security definer, and narrowly granted. The campaign tables let a rep read
-- and nothing more, which is right; a harness that has to write fixtures cannot
-- run under those policies. So it runs as the owner and is executable only by
-- service_role — a developer tool, never something the app can reach.
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
    -- KH002: on the demo branch, with no audience rows anywhere pointing at it,
    -- so what it qualifies for is exactly what this function creates.
    c_cust  constant uuid := '00000000-0000-0000-0000-000000000092';

    -- Coca and Pepsi by the case, sweets by the pack. From the base catalogue
    -- rather than invented here: a test that mints its own products would not
    -- exercise the unit conversion, which is where the engine is subtlest.
    c_coca  constant uuid := '00000000-0000-0000-0000-000000000c01';
    c_pepsi constant uuid := '00000000-0000-0000-0000-000000000c02';
    c_water constant uuid := '00000000-0000-0000-0000-000000000c03';
    c_sweet constant uuid := '00000000-0000-0000-0000-000000000c08';

    c_prog  constant uuid := 'ffffffff-0000-0000-0000-000000000001';
    c_seq   constant uuid := 'ffffffff-0000-0000-0000-000000000002';
    c_seq2  constant uuid := 'ffffffff-0000-0000-0000-000000000003';
    c_hi    constant uuid := 'ffffffff-0000-0000-0000-000000000004';  -- level: 10
    c_lo    constant uuid := 'ffffffff-0000-0000-0000-000000000005';  -- level: 5
    c_b2    constant uuid := 'ffffffff-0000-0000-0000-000000000006';
    c_budget constant uuid := 'ffffffff-0000-0000-0000-000000000007';

    v_muted uuid[];
begin
    -- Everything from here to the matching restore runs inside one block whose
    -- exception handler puts the catalogue back. A test that leaves the demo
    -- campaigns switched off would be worse than no test.
    begin
        select coalesce(array_agg(id), '{}') into v_muted
        from discount_sequence where is_active;

        update discount_sequence set is_active = false where id = any (v_muted);

        insert into discount_program (id, code, name, scope, is_active)
        values (c_prog, 'ZZ-TEST', 'Chương trình kiểm thử', 'line', true);

        insert into discount_sequence (
            id, program_id, code, name, break_by, reward,
            from_date, to_date, is_active
        )
        values (
            c_seq, c_prog, 'S1', 'Mức kiểm thử', 'qty', 'amount',
            current_date - 1, current_date + 1, true
        );

        insert into discount_condition_item (sequence_id, product_id, uom_code)
        values (c_seq, c_coca, 'CASE');

        insert into discount_break (id, sequence_id, line_ref, name, break_qty, disc_amt)
        values
            (c_hi, c_seq, 'B1', 'Mua 10', 10, 100000),
            (c_lo, c_seq, 'B2', 'Mua 5',   5,  40000);

        -- =====================================================================
        -- The ladder
        -- =====================================================================

        case_name := 'bậc thang: 12 thùng ăn mức 10 một suất';
        expected  := 'Mua 10|1';
        select coalesce(string_agg((e ->> 'break_name') || '|' || (e ->> 'portion'), ','), '-')
        into actual
        from jsonb_array_elements(
            calculate_promotions(c_cust, jsonb_build_array(
                jsonb_build_object('product_id', c_coca, 'uom_code', 'CASE', 'qty', 12))
            ) -> 'earned') e;
        passed := actual = expected;
        return next;

        case_name := 'bậc thang lặp: 30 thùng ăn mức 10 ba suất';
        expected  := 'Mua 10|3';
        select coalesce(string_agg((e ->> 'break_name') || '|' || (e ->> 'portion'), ','), '-')
        into actual
        from jsonb_array_elements(
            calculate_promotions(c_cust, jsonb_build_array(
                jsonb_build_object('product_id', c_coca, 'uom_code', 'CASE', 'qty', 30))
            ) -> 'earned') e;
        passed := actual = expected;
        return next;

        case_name := 'phần dư rơi xuống mức thấp hơn';
        expected  := 'Mua 10|1,Mua 5|1';
        select coalesce(string_agg((e ->> 'break_name') || '|' || (e ->> 'portion'), ','), '-')
        into actual
        from jsonb_array_elements(
            calculate_promotions(c_cust, jsonb_build_array(
                jsonb_build_object('product_id', c_coca, 'uom_code', 'CASE', 'qty', 17))
            ) -> 'earned') e;
        passed := actual = expected;
        return next;

        -- Ten cases is the level; a case short of it is nothing. The boundary is
        -- where an off-by-one lives.
        case_name := 'dưới ngưỡng thấp nhất thì không ăn';
        expected  := '-';
        select coalesce(string_agg(e ->> 'break_name', ','), '-')
        into actual
        from jsonb_array_elements(
            calculate_promotions(c_cust, jsonb_build_array(
                jsonb_build_object('product_id', c_coca, 'uom_code', 'CASE', 'qty', 4))
            ) -> 'earned') e;
        passed := actual = expected;
        return next;

        -- =====================================================================
        -- Unit conversion: the rule is written in cases, the basket in pieces
        -- =====================================================================

        case_name := 'quy đổi đơn vị: 240 lẻ = 10 thùng';
        expected  := 'Mua 10|1';
        select coalesce(string_agg((e ->> 'break_name') || '|' || (e ->> 'portion'), ','), '-')
        into actual
        from jsonb_array_elements(
            calculate_promotions(c_cust, jsonb_build_array(
                jsonb_build_object('product_id', c_coca, 'uom_code', 'PCS', 'qty', 240))
            ) -> 'earned') e;
        passed := actual = expected;
        return next;

        -- =====================================================================
        -- MaxLot
        -- =====================================================================

        update discount_break set max_lot = 2 where id = c_hi;

        case_name := 'MaxLot chặn số suất';
        expected  := '2';
        select coalesce(max(e ->> 'portion'), '-')
        into actual
        from jsonb_array_elements(
            calculate_promotions(c_cust, jsonb_build_array(
                jsonb_build_object('product_id', c_coca, 'uom_code', 'CASE', 'qty', 30))
            ) -> 'earned') e
        where e ->> 'break_name' = 'Mua 10';
        passed := actual = expected;
        return next;

        update discount_break set max_lot = 0 where id = c_hi;

        -- =====================================================================
        -- BreakBoundType
        -- =====================================================================

        update discount_sequence set break_bound = 'bounded' where id = c_seq;

        case_name := 'bounded: một suất và không xét mức thấp hơn';
        expected  := 'Mua 10|1';
        select coalesce(string_agg((e ->> 'break_name') || '|' || (e ->> 'portion'), ','), '-')
        into actual
        from jsonb_array_elements(
            calculate_promotions(c_cust, jsonb_build_array(
                jsonb_build_object('product_id', c_coca, 'uom_code', 'CASE', 'qty', 30))
            ) -> 'earned') e;
        passed := actual = expected;
        return next;

        update discount_break set break_qty_upper = 15 where id = c_hi;

        case_name := 'dải trên: vượt trần thì rơi xuống mức dưới';
        expected  := 'Mua 5';
        select coalesce(string_agg(e ->> 'break_name', ','), '-')
        into actual
        from jsonb_array_elements(
            calculate_promotions(c_cust, jsonb_build_array(
                jsonb_build_object('product_id', c_coca, 'uom_code', 'CASE', 'qty', 20))
            ) -> 'earned') e;
        passed := actual = expected;
        return next;

        update discount_break set break_qty_upper = 0 where id = c_hi;
        update discount_sequence set break_bound = 'lower' where id = c_seq;

        -- =====================================================================
        -- OnlyMaxLineRef and ExactQty
        -- =====================================================================

        update discount_sequence set only_once = true where id = c_seq;

        case_name := 'chỉ một lần mỗi đơn';
        expected  := '1';
        select count(*)::text into actual
        from jsonb_array_elements(
            calculate_promotions(c_cust, jsonb_build_array(
                jsonb_build_object('product_id', c_coca, 'uom_code', 'CASE', 'qty', 17))
            ) -> 'earned') e;
        passed := actual = expected;
        return next;

        update discount_sequence set only_once = false, exact_qty = true where id = c_seq;

        case_name := 'đúng chằn: 13 thùng không ăn';
        expected  := '-';
        select coalesce(string_agg(e ->> 'break_name', ','), '-')
        into actual
        from jsonb_array_elements(
            calculate_promotions(c_cust, jsonb_build_array(
                jsonb_build_object('product_id', c_coca, 'uom_code', 'CASE', 'qty', 13))
            ) -> 'earned') e;
        passed := actual = expected;
        return next;

        case_name := 'đúng chằn: 10 thùng ăn mức 10';
        expected  := 'Mua 10';
        select coalesce(string_agg(e ->> 'break_name', ','), '-')
        into actual
        from jsonb_array_elements(
            calculate_promotions(c_cust, jsonb_build_array(
                jsonb_build_object('product_id', c_coca, 'uom_code', 'CASE', 'qty', 10))
            ) -> 'earned') e;
        passed := actual = expected;
        return next;

        update discount_sequence set exact_qty = false where id = c_seq;

        -- =====================================================================
        -- Required items — the check a hand test let through once already
        -- =====================================================================

        update discount_sequence set required_type = 'qty' where id = c_seq;
        update discount_condition_item set required_value = 4 where sequence_id = c_seq;
        insert into discount_condition_item (sequence_id, product_id, uom_code, required_value)
        values (c_seq, c_pepsi, 'CASE', 4);

        case_name := 'SP bắt buộc vắng mặt thì cả CT không ăn';
        expected  := '-';
        select coalesce(string_agg(e ->> 'break_name', ','), '-')
        into actual
        from jsonb_array_elements(
            calculate_promotions(c_cust, jsonb_build_array(
                jsonb_build_object('product_id', c_coca, 'uom_code', 'CASE', 'qty', 12))
            ) -> 'earned') e;
        passed := actual = expected;
        return next;

        -- Four cases of Pepsi, not five: it satisfies the requirement without
        -- reaching a level of its own. A line rule measures each condition item
        -- separately, so five would have earned the lower level on Pepsi too and
        -- the case would have been testing two things at once.
        case_name := 'đủ mọi SP bắt buộc thì ăn';
        expected  := 'Mua 10';
        select coalesce(string_agg(e ->> 'break_name', ','), '-')
        into actual
        from jsonb_array_elements(
            calculate_promotions(c_cust, jsonb_build_array(
                jsonb_build_object('product_id', c_coca, 'uom_code', 'CASE', 'qty', 12),
                jsonb_build_object('product_id', c_pepsi, 'uom_code', 'CASE', 'qty', 4))
            ) -> 'earned') e;
        passed := actual = expected;
        return next;

        -- "Buy any N of these": the values stop mattering, the count starts.
        update discount_sequence set required_type = 'any_n', required_number = 3
        where id = c_seq;
        insert into discount_condition_item (sequence_id, product_id, uom_code)
        values (c_seq, c_water, 'CASE');

        case_name := 'bất kỳ N: hai mặt hàng thì chưa đủ';
        expected  := '-';
        select coalesce(string_agg(e ->> 'break_name', ','), '-')
        into actual
        from jsonb_array_elements(
            calculate_promotions(c_cust, jsonb_build_array(
                jsonb_build_object('product_id', c_coca, 'uom_code', 'CASE', 'qty', 12),
                jsonb_build_object('product_id', c_pepsi, 'uom_code', 'CASE', 'qty', 4))
            ) -> 'earned') e;
        passed := actual = expected;
        return next;

        case_name := 'bất kỳ N: đủ ba mặt hàng thì ăn';
        expected  := 'Mua 10';
        select coalesce(string_agg(e ->> 'break_name', ','), '-')
        into actual
        from jsonb_array_elements(
            calculate_promotions(c_cust, jsonb_build_array(
                jsonb_build_object('product_id', c_coca, 'uom_code', 'CASE', 'qty', 12),
                jsonb_build_object('product_id', c_pepsi, 'uom_code', 'CASE', 'qty', 4),
                jsonb_build_object('product_id', c_water, 'uom_code', 'CASE', 'qty', 1))
            ) -> 'earned') e;
        passed := actual = expected;
        return next;

        delete from discount_condition_item
        where sequence_id = c_seq and product_id in (c_pepsi, c_water);
        update discount_sequence set required_type = 'none', required_number = 0
        where id = c_seq;
        update discount_condition_item set required_value = 0 where sequence_id = c_seq;

        -- =====================================================================
        -- Percentages: a threshold, not a multiplier, on what is still charged
        -- =====================================================================

        update discount_sequence set reward = 'percent' where id = c_seq;
        update discount_break set disc_amt = 5 where id = c_hi;

        -- 30 cases clears the level three times. A percentage does not deepen.
        case_name := '% không nhân theo số suất';
        expected  := '5.0000|342000';
        select coalesce(max((e ->> 'percent') || '|' || (e ->> 'discount_amount')), '-')
        into actual
        from jsonb_array_elements(
            calculate_promotions(c_cust, jsonb_build_array(
                jsonb_build_object('product_id', c_coca, 'uom_code', 'CASE', 'qty', 30))
            ) -> 'earned') e;
        passed := actual = expected;
        return next;

        -- A money rule on the same product first, then the percentage: the base
        -- has shrunk by what was already given back.
        insert into discount_sequence (
            id, program_id, code, name, break_by, reward, priority,
            from_date, to_date, is_active
        )
        values (
            c_seq2, c_prog, 'S2', 'Giảm tiền trước', 'qty', 'amount', 99,
            current_date - 1, current_date + 1, true
        );
        insert into discount_condition_item (sequence_id, product_id, uom_code)
        values (c_seq2, c_coca, 'CASE');
        insert into discount_break (id, sequence_id, line_ref, name, break_qty, disc_amt)
        values (c_b2, c_seq2, 'B1', 'Giảm tiền', 10, 100000);

        -- 12 cases: 2.736.000 gross, less 100.000 already given, times 5%.
        case_name := '% tính trên số tiền sau giảm';
        expected  := '131800';
        select coalesce(max(e ->> 'discount_amount'), '-')
        into actual
        from jsonb_array_elements(
            calculate_promotions(c_cust, jsonb_build_array(
                jsonb_build_object('product_id', c_coca, 'uom_code', 'CASE', 'qty', 12))
            ) -> 'earned') e
        where e ->> 'sequence_code' = 'S1';
        passed := actual = expected;
        return next;

        -- =====================================================================
        -- ExcludePromo: firing one silences another
        -- =====================================================================

        insert into discount_exclusion (sequence_id, excludes_id) values (c_seq2, c_seq);

        case_name := 'CT nổ trước loại CT bị cấm';
        expected  := 'S2';
        select coalesce(string_agg(e ->> 'sequence_code', ','), '-')
        into actual
        from jsonb_array_elements(
            calculate_promotions(c_cust, jsonb_build_array(
                jsonb_build_object('product_id', c_coca, 'uom_code', 'CASE', 'qty', 12))
            ) -> 'earned') e;
        passed := actual = expected;
        return next;

        delete from discount_exclusion where sequence_id = c_seq2;

        -- =====================================================================
        -- IsDeductQtyAmt: which basket a rule is measured against
        -- =====================================================================

        -- S2 fires first and eats ten cases; S1 measured against the shared pool
        -- then sees two and cannot reach its own level of ten.
        update discount_sequence set reward = 'amount', deducts_shared = true where id = c_seq;
        update discount_break set disc_amt = 100000 where id = c_hi;

        case_name := 'đo trên bể chung: CT sau thấy phần còn lại';
        expected  := 'S2';
        select coalesce(string_agg(e ->> 'sequence_code', ','), '-')
        into actual
        from jsonb_array_elements(
            calculate_promotions(c_cust, jsonb_build_array(
                jsonb_build_object('product_id', c_coca, 'uom_code', 'CASE', 'qty', 12))
            ) -> 'earned') e;
        passed := actual = expected;
        return next;

        case_name := 'đo trên giỏ gốc: cả hai CT cùng ăn';
        expected  := 'S1,S2';
        update discount_sequence set deducts_shared = false where id = c_seq;
        select coalesce(string_agg(e ->> 'sequence_code', ',' order by e ->> 'sequence_code'), '-')
        into actual
        from jsonb_array_elements(
            calculate_promotions(c_cust, jsonb_build_array(
                jsonb_build_object('product_id', c_coca, 'uom_code', 'CASE', 'qty', 12))
            ) -> 'earned') e;
        passed := actual = expected;
        return next;

        delete from discount_break where id = c_b2;
        delete from discount_condition_item where sequence_id = c_seq2;
        delete from discount_sequence where id = c_seq2;

        -- =====================================================================
        -- Gifts
        -- =====================================================================

        update discount_sequence set reward = 'free_item' where id = c_seq;
        insert into discount_free_item (sequence_id, product_id, uom_code, free_qty, sort_order)
        values
            (c_seq, c_pepsi, 'CASE', 2, 1),
            (c_seq, c_sweet, 'PACK', 1, 2);

        case_name := 'quà nhân theo số suất';
        expected  := 'NGK002|4,BK003|2';
        select coalesce(string_agg((f ->> 'product_code') || '|' || (f ->> 'qty'), ','), '-')
        into actual
        from jsonb_array_elements(
            calculate_promotions(c_cust, jsonb_build_array(
                jsonb_build_object('product_id', c_coca, 'uom_code', 'CASE', 'qty', 20))
            ) -> 'earned') e,
            jsonb_array_elements(e -> 'free_items') f;
        passed := actual = expected;
        return next;

        case_name := 'tên đơn vị đi kèm mã đơn vị';
        expected  := 'CASE|Thung';
        select coalesce(max((f ->> 'uom_code') || '|' || (f ->> 'uom_name')), '-')
        into actual
        from jsonb_array_elements(
            calculate_promotions(c_cust, jsonb_build_array(
                jsonb_build_object('product_id', c_coca, 'uom_code', 'CASE', 'qty', 10))
            ) -> 'earned') e,
            jsonb_array_elements(e -> 'free_items') f
        where f ->> 'product_code' = 'NGK002';
        passed := actual = expected;
        return next;

        -- A list of alternatives pre-selects the first and only the first.
        update discount_sequence set auto_free_item = false where id = c_seq;

        case_name := 'quà OR: chỉ món đầu được chọn sẵn';
        expected  := 'NGK002|true,BK003|false';
        select coalesce(string_agg((f ->> 'product_code') || '|' || (f ->> 'chosen'), ','), '-')
        into actual
        from jsonb_array_elements(
            calculate_promotions(c_cust, jsonb_build_array(
                jsonb_build_object('product_id', c_coca, 'uom_code', 'CASE', 'qty', 10))
            ) -> 'earned') e,
            jsonb_array_elements(e -> 'free_items') f;
        passed := actual = expected;
        return next;

        update discount_sequence set auto_free_item = true, donate_group = true where id = c_seq;

        case_name := 'chia đều: bể 3 chia cho 2 món';
        expected  := 'NGK002|1,BK003|1';
        select coalesce(string_agg((f ->> 'product_code') || '|' || (f ->> 'qty'), ','), '-')
        into actual
        from jsonb_array_elements(
            calculate_promotions(c_cust, jsonb_build_array(
                jsonb_build_object('product_id', c_coca, 'uom_code', 'CASE', 'qty', 10))
            ) -> 'earned') e,
            jsonb_array_elements(e -> 'free_items') f;
        passed := actual = expected;
        return next;

        update discount_sequence set donate_group = false, reward = 'amount',
            convert_amount_to_goods = true where id = c_seq;
        update discount_break set disc_amt = 500000 where id = c_hi;
        delete from discount_free_item where sequence_id = c_seq and product_id = c_sweet;
        update discount_free_item set promo_price = 200000 where sequence_id = c_seq;

        case_name := 'quy tiền ra hàng: 500k ở giá 200k = 2, tiền về 0';
        expected  := '0|NGK002|2';
        select coalesce(max((e ->> 'discount_amount') || '|' || (f ->> 'product_code')
                            || '|' || (f ->> 'qty')), '-')
        into actual
        from jsonb_array_elements(
            calculate_promotions(c_cust, jsonb_build_array(
                jsonb_build_object('product_id', c_coca, 'uom_code', 'CASE', 'qty', 10))
            ) -> 'earned') e,
            jsonb_array_elements(e -> 'free_items') f;
        passed := actual = expected;
        return next;

        update discount_sequence set convert_amount_to_goods = false, reward = 'free_item'
        where id = c_seq;
        update discount_free_item set promo_price = 0 where sequence_id = c_seq;

        -- =====================================================================
        -- Budget
        -- =====================================================================

        update discount_sequence set reward = 'amount' where id = c_seq;
        update discount_break set disc_amt = 100000 where id = c_hi;

        insert into discount_budget (id, code, name, counts, alloc_by, from_date, to_date)
        values (c_budget, 'ZZ-NS', 'Ngân sách kiểm thử', 'amount', 'route',
                current_date - 1, current_date + 1);

        insert into discount_budget_allocation (budget_id, target_id, allocated, spent)
        select c_budget, r.id, 50000, 0
        from sales_route r
        where r.salesperson_id = current_salesperson_id() and r.is_active
        order by r.code limit 1;

        update discount_sequence set budget_id = c_budget where id = c_seq;

        case_name := 'hết ngân sách thì CT không áp dụng';
        expected  := '-';
        select coalesce(string_agg(e ->> 'sequence_code', ','), '-')
        into actual
        from jsonb_array_elements(
            calculate_promotions(c_cust, jsonb_build_array(
                jsonb_build_object('product_id', c_coca, 'uom_code', 'CASE', 'qty', 10))
            ) -> 'earned') e;
        passed := actual = expected;
        return next;

        case_name := 'và được báo lại kèm số muốn / số còn';
        expected  := '100000|50000.0000';
        select coalesce(max((o ->> 'wanted') || '|' || (o ->> 'remaining')), '-')
        into actual
        from jsonb_array_elements(
            calculate_promotions(c_cust, jsonb_build_array(
                jsonb_build_object('product_id', c_coca, 'uom_code', 'CASE', 'qty', 10))
            ) -> 'out_of_budget') o;
        passed := actual = expected;
        return next;

        update discount_budget_allocation set allocated = 500000 where budget_id = c_budget;

        case_name := 'ngân sách đủ thì CT áp dụng lại';
        expected  := 'S1';
        select coalesce(string_agg(e ->> 'sequence_code', ','), '-')
        into actual
        from jsonb_array_elements(
            calculate_promotions(c_cust, jsonb_build_array(
                jsonb_build_object('product_id', c_coca, 'uom_code', 'CASE', 'qty', 10))
            ) -> 'earned') e;
        passed := actual = expected;
        return next;

        -- =====================================================================
        -- Combo: the ladder counts complete bundles
        -- =====================================================================

        -- One bundle is 2 Coca and 1 Pepsi. Levels are read as bundle counts, so
        -- "Mua 10" here means ten bundles.
        update discount_sequence
        set is_bundle = true, reward = 'amount', budget_id = null
        where id = c_seq;
        update discount_condition_item set bundle_qty = 2 where sequence_id = c_seq;
        insert into discount_condition_item (sequence_id, product_id, uom_code, bundle_qty)
        values (c_seq, c_pepsi, 'CASE', 1);
        update discount_break set break_qty = 3, name = 'Ba bộ' where id = c_hi;
        update discount_break set break_qty = 1, name = 'Một bộ' where id = c_lo;

        -- 12 Coca makes 6, 4 Pepsi makes 4: four bundles, so the three-bundle
        -- level once, with one bundle left for the one-bundle level.
        case_name := 'combo: số bộ là min của các tỉ lệ';
        expected  := 'Ba bộ|1,Một bộ|1';
        select coalesce(string_agg((e ->> 'break_name') || '|' || (e ->> 'portion'), ','), '-')
        into actual
        from jsonb_array_elements(
            calculate_promotions(c_cust, jsonb_build_array(
                jsonb_build_object('product_id', c_coca, 'uom_code', 'CASE', 'qty', 12),
                jsonb_build_object('product_id', c_pepsi, 'uom_code', 'CASE', 'qty', 4))
            ) -> 'earned') e;
        passed := actual = expected;
        return next;

        case_name := 'combo: thiếu một thành phần thì không có bộ nào';
        expected  := '-';
        select coalesce(string_agg(e ->> 'break_name', ','), '-')
        into actual
        from jsonb_array_elements(
            calculate_promotions(c_cust, jsonb_build_array(
                jsonb_build_object('product_id', c_coca, 'uom_code', 'CASE', 'qty', 30))
            ) -> 'earned') e;
        passed := actual = expected;
        return next;

        -- Six Coca and three Pepsi is three bundles of each, evenly. Seven Coca
        -- is three bundles and one case spare, which an exact rule refuses.
        update discount_sequence set exact_qty = true where id = c_seq;

        case_name := 'combo đúng chằn: chia hết và bằng nhau thì ăn';
        expected  := 'Ba bộ';
        select coalesce(string_agg(e ->> 'break_name', ','), '-')
        into actual
        from jsonb_array_elements(
            calculate_promotions(c_cust, jsonb_build_array(
                jsonb_build_object('product_id', c_coca, 'uom_code', 'CASE', 'qty', 6),
                jsonb_build_object('product_id', c_pepsi, 'uom_code', 'CASE', 'qty', 3))
            ) -> 'earned') e;
        passed := actual = expected;
        return next;

        case_name := 'combo đúng chằn: dư một thành phần thì không ăn';
        expected  := '-';
        select coalesce(string_agg(e ->> 'break_name', ','), '-')
        into actual
        from jsonb_array_elements(
            calculate_promotions(c_cust, jsonb_build_array(
                jsonb_build_object('product_id', c_coca, 'uom_code', 'CASE', 'qty', 7),
                jsonb_build_object('product_id', c_pepsi, 'uom_code', 'CASE', 'qty', 3))
            ) -> 'earned') e;
        passed := actual = expected;
        return next;

        update discount_sequence set exact_qty = false, is_bundle = false where id = c_seq;
        delete from discount_condition_item where sequence_id = c_seq and product_id = c_pepsi;
        update discount_condition_item set bundle_qty = 0 where sequence_id = c_seq;
        update discount_break set break_qty = 10, name = 'Mua 10' where id = c_hi;
        update discount_break set break_qty = 5, name = 'Mua 5' where id = c_lo;

        -- =====================================================================
        -- An empty basket earns nothing and does not fall over
        -- =====================================================================

        case_name := 'giỏ rỗng: không ăn gì, không lỗi';
        expected  := '0|0';
        select (t.r ->> 'total_discount') || '|' ||
               jsonb_array_length(t.r -> 'earned')::text
        into actual
        from (select calculate_promotions(c_cust, '[]'::jsonb) as r) t;
        passed := actual = expected;
        return next;

        -- ---------------------------------------------------------------------
        -- Put everything back, whatever happened above.
        -- ---------------------------------------------------------------------
        -- The sequence points at the budget, so the reference goes first.
        update discount_sequence set budget_id = null where program_id = c_prog;
        delete from discount_budget where id = c_budget;
        delete from discount_free_item where sequence_id = c_seq;
        delete from discount_break where sequence_id in (c_seq, c_seq2);
        delete from discount_condition_item where sequence_id in (c_seq, c_seq2);
        delete from discount_sequence where program_id = c_prog;
        delete from discount_program where id = c_prog;
        update discount_sequence set is_active = true where id = any (v_muted);

    exception when others then
        -- The catalogue matters more than the diagnosis. Restore first, then let
        -- the error out with its own message intact.
        -- The sequence points at the budget, so the reference goes first.
        update discount_sequence set budget_id = null where program_id = c_prog;
        delete from discount_budget where id = c_budget;
        delete from discount_free_item where sequence_id = c_seq;
        delete from discount_break where sequence_id in (c_seq, c_seq2);
        delete from discount_condition_item where sequence_id in (c_seq, c_seq2);
        delete from discount_sequence where program_id = c_prog;
        delete from discount_program where id = c_prog;
        update discount_sequence set is_active = true where id = any (v_muted);
        raise;
    end;
end;
$$;

revoke execute on function test_calculate_promotions() from public;
revoke execute on function test_calculate_promotions() from authenticated;
grant execute on function test_calculate_promotions() to service_role;

comment on function test_calculate_promotions() is
    'Repeatable checks over calculate_promotions. Deactivates the live campaigns '
    'while it runs and restores them before returning, including on error. Best '
    'run inside begin/rollback all the same.';
