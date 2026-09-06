-- =============================================================================
-- Demo khuyến mãi
--
-- Four campaigns, chosen to cover every branch of the engine rather than to look
-- plausible on a slide: one of each scope, one of each reward, both break_by
-- kinds, a ceiling, a multi-level ladder, an OR list of gifts, and a rule that
-- only some outlets qualify for.
--
--   KM-CASE  line  / qty    / free_item       buy 5 cases of Coca, get 1 Pepsi
--                                             case. Two levels and a MaxLot, so
--                                             the ladder and the ceiling are both
--                                             exercised.
--   KM-BK    line  / qty    / amount_or_item  buy 10 packs of Alpenliebe: take
--                                             30k off or a box of Oreo. Two gifts
--                                             as alternatives (OR).
--   KM-NGK   group / amount / percent         spend 2m across soft drinks, 3% off.
--   KM-DOC   order / amount / amount          spend 5m on the order, 100k off.
--
-- Idempotent, so re-running after a reset does not double the rows.
-- =============================================================================

insert into discount_program (id, code, name, scope, disc_class)
values
    ('00000000-0000-0000-0000-000000010001', 'KM-CASE',
     'Mua thùng tặng thùng', 'line', 'II'),
    ('00000000-0000-0000-0000-000000010002', 'KM-BK',
     'Bánh kẹo - chọn quà hoặc giảm tiền', 'line', 'II'),
    ('00000000-0000-0000-0000-000000010003', 'KM-NGK',
     'Nước giải khát - chiết khấu theo nhóm', 'group', 'GI'),
    ('00000000-0000-0000-0000-000000010004', 'KM-DOC',
     'Giảm trên tổng đơn', 'order', 'CI')
on conflict (code) do nothing;

insert into discount_sequence (
    id, program_id, code, name, break_by, reward,
    auto_free_item, exclude_other_disc, priority,
    from_date, to_date, open_ended, branch_id
)
values
    -- Buy cases of Coca, get cases of Pepsi. AutoFreeItem: one gift, always given.
    ('00000000-0000-0000-0000-000000011001', '00000000-0000-0000-0000-000000010001',
     'S1', 'Mua 5 thùng Coca tặng 1 thùng Pepsi', 'qty', 'free_item',
     true, false, 10, current_date - 30, current_date + 60, false, null),

    -- The rep picks: 30.000 đ off, or one of two boxes of sweets.
    ('00000000-0000-0000-0000-000000011002', '00000000-0000-0000-0000-000000010002',
     'S1', 'Mua 10 gói kẹo - giảm tiền hoặc nhận quà', 'qty', 'amount_or_item',
     false, false, 5, current_date - 20, current_date + 40, false, null),

    -- Group by money across the soft-drink range.
    ('00000000-0000-0000-0000-000000011003', '00000000-0000-0000-0000-000000010003',
     'S1', 'Mua nước giải khát từ 2 triệu - giảm 3%', 'amount', 'percent',
     true, false, 0, current_date - 15, current_date + 90, false, null),

    -- Whole order, demo branch only, so the branch filter is exercised.
    ('00000000-0000-0000-0000-000000011004', '00000000-0000-0000-0000-000000010004',
     'S1', 'Đơn từ 5 triệu - giảm 100.000 đ', 'amount', 'amount',
     true, false, 0, current_date - 10, current_date + 120, false,
     '00000000-0000-0000-0000-000000000010')
on conflict (program_id, code) do nothing;

-- KM-BK is only for the two outlets that stock sweets in volume. Everything else
-- has no audience rows, which means the whole branch.
insert into discount_audience (sequence_id, kind, target_id)
values
    ('00000000-0000-0000-0000-000000011002', 'customer',
     '00000000-0000-0000-0000-000000000096'),
    ('00000000-0000-0000-0000-000000011002', 'customer',
     '00000000-0000-0000-0000-000000000091')
on conflict (sequence_id, kind, target_id) do nothing;

insert into discount_break (id, sequence_id, line_ref, name, break_qty, break_amt, disc_amt, max_lot)
values
    -- A ladder: ten cases is worth proportionally more than five, and the levels
    -- are walked highest first so ten does not decompose into two fives.
    ('00000000-0000-0000-0000-000000012001', '00000000-0000-0000-0000-000000011001',
     'B2', 'Mua 10 thùng', 10, 0, 0, 0),
    ('00000000-0000-0000-0000-000000012002', '00000000-0000-0000-0000-000000011001',
     'B1', 'Mua 5 thùng', 5, 0, 0, 3),

    ('00000000-0000-0000-0000-000000012003', '00000000-0000-0000-0000-000000011002',
     'B1', 'Mua 10 gói', 10, 0, 30000, 2),

    ('00000000-0000-0000-0000-000000012004', '00000000-0000-0000-0000-000000011003',
     'B1', 'Từ 2.000.000 đ', 0, 2000000, 3, 0),

    ('00000000-0000-0000-0000-000000012005', '00000000-0000-0000-0000-000000011004',
     'B1', 'Từ 5.000.000 đ', 0, 5000000, 100000, 1)
on conflict (sequence_id, line_ref) do nothing;

-- What has to be bought. Note the unit: KM-CASE is written in cases, KM-BK in
-- packs, and the engine converts the cart into whichever unit the rule names.
insert into discount_condition_item (sequence_id, product_id, uom_code)
values
    -- Coca-Cola, by the case
    ('00000000-0000-0000-0000-000000011001', '00000000-0000-0000-0000-000000000c01', 'CASE'),

    -- Alpenliebe, by the pack
    ('00000000-0000-0000-0000-000000011002', '00000000-0000-0000-0000-000000000c08', 'PACK'),

    -- The whole soft-drink range, by the case
    ('00000000-0000-0000-0000-000000011003', '00000000-0000-0000-0000-000000000c01', 'CASE'),
    ('00000000-0000-0000-0000-000000011003', '00000000-0000-0000-0000-000000000c02', 'CASE'),
    ('00000000-0000-0000-0000-000000011003', '00000000-0000-0000-0000-000000000c03', 'CASE'),
    ('00000000-0000-0000-0000-000000011003', '00000000-0000-0000-0000-000000000c04', 'CASE'),
    ('00000000-0000-0000-0000-000000011003', '00000000-0000-0000-0000-000000000c05', 'CASE')
on conflict (sequence_id, product_id, uom_code) do nothing;

insert into discount_free_item (sequence_id, product_id, uom_code, free_qty, sort_order)
values
    -- One gift, always given.
    ('00000000-0000-0000-0000-000000011001', '00000000-0000-0000-0000-000000000c02', 'CASE', 1, 1),

    -- Two alternatives: auto_free_item is false on this rule, so the rep takes
    -- one of them — or the 30.000 đ instead, because the reward is amount_or_item.
    ('00000000-0000-0000-0000-000000011002', '00000000-0000-0000-0000-000000000c06', 'PCS', 2, 1),
    ('00000000-0000-0000-0000-000000011002', '00000000-0000-0000-0000-000000000c07', 'PCS', 1, 2)
on conflict (sequence_id, product_id, uom_code) do nothing;
