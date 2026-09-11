-- =============================================================================
-- The work-type catalogue
--
-- OM_WorkWithCategory as head office would fill it: the day-to-day kinds of
-- work a rep plans, one of each shape so every branch of the form is reachable
-- -- with a customer, with another rep, with the distributor, and with nobody.
-- =============================================================================

insert into work_with_category
    (code, name, field_training, trade_program, object_kind, min_sales, max_sales, sort_order)
values
    ('WW-ROUTE',   'Đi tuyến cùng khách hàng',      false, false, 'customer',    null,      null,       10),
    ('WW-TRADE',   'Triển khai chương trình trưng bày', false, true,  'customer',    null,      null,       20),
    ('WW-NEWCUST', 'Phát triển khách hàng mới',     false, false, 'customer',    null,      null,       30),
    ('WW-COACH',   'Kèm cặp nhân viên bán hàng',    true,  false, 'salesperson', null,      null,       40),
    ('WW-NPP',     'Làm việc với nhà phân phối',    false, false, 'branch',      null,      null,       50),
    -- The one with no object: a training day is with nobody, and the form has
    -- to be able to say so.
    ('WW-TRAIN',   'Đào tạo nội bộ',                false, false, null,          null,      null,       60),
    ('WW-BIGDAY',  'Ngày bán hàng trọng điểm',      false, true,  'customer',    5000000,   50000000,   70)
on conflict (code) do nothing;
