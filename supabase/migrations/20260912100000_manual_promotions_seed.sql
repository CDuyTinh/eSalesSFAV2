-- =============================================================================
-- Demo khuyến mãi tay
--
-- One of each kind, because the screen renders the three differently and a seed
-- with only money in it would leave the goods branch untested.
--
--   KMT-CK3   percent, fixed. Three per cent off, applied or not.
--   KMT-TIEN  amount, editable. Up to 100.000 đ — the rep decides how much of
--             it this shop has earned, which is the whole point of AllowEdit.
--   KMT-QUA   goods. Two boxes of Oreo, no argument about the amount.
-- =============================================================================

insert into manual_promotion (id, code, name, promo_type, value, allow_edit,
                              from_date, to_date, branch_id)
values
    (
        '00000000-0000-0000-0000-000000020001',
        'KMT-CK3',
        'Chiết khấu tay 3%',
        'percent', 3, false,
        current_date - 30, current_date + 180,
        null
    ),
    (
        '00000000-0000-0000-0000-000000020002',
        'KMT-TIEN',
        'Hỗ trợ khách hàng - tối đa 100.000 đ',
        'amount', 100000, true,
        current_date - 30, current_date + 180,
        null
    ),
    (
        '00000000-0000-0000-0000-000000020003',
        'KMT-QUA',
        'Tặng quà tri ân',
        'free_item', 0, false,
        current_date - 15, current_date + 120,
        -- Demo branch only, so the branch filter is exercised on this side too.
        '00000000-0000-0000-0000-000000000010'
    )
on conflict (code) do nothing;

insert into manual_promotion_item (promotion_id, product_id, uom_code, qty, sort_order)
values
    ('00000000-0000-0000-0000-000000020003',
     '00000000-0000-0000-0000-000000000c06', 'PCS', 2, 1)
on conflict (promotion_id, product_id, uom_code) do nothing;
