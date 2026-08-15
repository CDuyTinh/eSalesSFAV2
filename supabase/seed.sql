-- =============================================================================
-- Development seed data
--
-- Deterministic UUIDs so the set can be re-applied and so the Android app can
-- hard-code fixtures in tests. Everything is idempotent.
--
-- UUID scheme: the last two hex digits before the counter identify the table.
--   ..10 branch     ..20 province   ..3x district   ..4x ward
--   ..5x class      ..6x channel    ..7x shop type  ..8x salesperson
--   ..9x customer   ..a1 route
--
-- Auth users are NOT created here (GoTrue owns password hashing) — run
-- scripts/seed_auth_users.ps1 afterwards, which creates the logins and links
-- them to the salesperson rows below.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- Branch
-- -----------------------------------------------------------------------------
insert into branch (id, code, name, address, lat, lng) values
    ('00000000-0000-0000-0000-000000000010', 'BR01', 'NPP Mien Dong',
     '12 Duong 30/4, Thu Dau Mot, Binh Duong', 10.980400, 106.651900)
on conflict (id) do nothing;

-- -----------------------------------------------------------------------------
-- Geography
-- -----------------------------------------------------------------------------
insert into province (id, code, name) values
    ('00000000-0000-0000-0000-000000000020', 'VN-57', 'Binh Duong')
on conflict (id) do nothing;

insert into district (id, province_id, code, name) values
    ('00000000-0000-0000-0000-000000000031', '00000000-0000-0000-0000-000000000020', 'BD-TDM', 'Thu Dau Mot'),
    ('00000000-0000-0000-0000-000000000032', '00000000-0000-0000-0000-000000000020', 'BD-TA',  'Thuan An')
on conflict (id) do nothing;

insert into ward (id, district_id, code, name) values
    ('00000000-0000-0000-0000-000000000041', '00000000-0000-0000-0000-000000000031', 'BD-TDM-PH', 'Phu Hoa'),
    ('00000000-0000-0000-0000-000000000042', '00000000-0000-0000-0000-000000000031', 'BD-TDM-CN', 'Chanh Nghia'),
    ('00000000-0000-0000-0000-000000000043', '00000000-0000-0000-0000-000000000032', 'BD-TA-LT',  'Lai Thieu')
on conflict (id) do nothing;

-- -----------------------------------------------------------------------------
-- Classification
-- -----------------------------------------------------------------------------
insert into customer_class (id, code, name) values
    ('00000000-0000-0000-0000-000000000051', 'A', 'Loai A'),
    ('00000000-0000-0000-0000-000000000052', 'B', 'Loai B'),
    ('00000000-0000-0000-0000-000000000053', 'C', 'Loai C')
on conflict (id) do nothing;

insert into sales_channel (id, code, name) values
    ('00000000-0000-0000-0000-000000000061', 'GT', 'General Trade'),
    ('00000000-0000-0000-0000-000000000062', 'MT', 'Modern Trade')
on conflict (id) do nothing;

insert into shop_type (id, code, name) values
    ('00000000-0000-0000-0000-000000000071', 'TH', 'Tap hoa'),
    ('00000000-0000-0000-0000-000000000072', 'SM', 'Sieu thi mini'),
    ('00000000-0000-0000-0000-000000000073', 'QC', 'Quan cafe')
on conflict (id) do nothing;

-- -----------------------------------------------------------------------------
-- Salespeople (user_id linked later by the auth seeding script)
-- -----------------------------------------------------------------------------
insert into salesperson (id, code, full_name, branch_id, phone, allow_multi_device) values
    ('00000000-0000-0000-0000-000000000081', 'nvbh01', 'Tran Van Nam',
     '00000000-0000-0000-0000-000000000010', '0901234567', true),
    ('00000000-0000-0000-0000-000000000082', 'nvbh02', 'Le Thi Hoa',
     '00000000-0000-0000-0000-000000000010', '0907654321', true)
on conflict (id) do nothing;

-- -----------------------------------------------------------------------------
-- Customers — clustered around Thu Dau Mot so GPS check-in is testable
-- -----------------------------------------------------------------------------
insert into customer (id, code, name, branch_id, phone, address, ward_id, lat, lng,
                      class_id, channel_id, shop_type_id) values
    ('00000000-0000-0000-0000-000000000091', 'KH001', 'Tap hoa Minh Anh',
     '00000000-0000-0000-0000-000000000010', '0281234001', '45 Nguyen Trai, Phu Hoa',
     '00000000-0000-0000-0000-000000000041', 10.981200, 106.652400,
     '00000000-0000-0000-0000-000000000051', '00000000-0000-0000-0000-000000000061', '00000000-0000-0000-0000-000000000071'),
    ('00000000-0000-0000-0000-000000000092', 'KH002', 'Tap hoa Ba Bay',
     '00000000-0000-0000-0000-000000000010', '0281234002', '112 Yersin, Phu Hoa',
     '00000000-0000-0000-0000-000000000041', 10.978900, 106.649100,
     '00000000-0000-0000-0000-000000000052', '00000000-0000-0000-0000-000000000061', '00000000-0000-0000-0000-000000000071'),
    ('00000000-0000-0000-0000-000000000093', 'KH003', 'Sieu thi mini Thanh Cong',
     '00000000-0000-0000-0000-000000000010', '0281234003', '8 Cach Mang Thang 8, Chanh Nghia',
     '00000000-0000-0000-0000-000000000042', 10.975600, 106.655300,
     '00000000-0000-0000-0000-000000000051', '00000000-0000-0000-0000-000000000062', '00000000-0000-0000-0000-000000000072'),
    ('00000000-0000-0000-0000-000000000094', 'KH004', 'Quan cafe Sang',
     '00000000-0000-0000-0000-000000000010', '0281234004', '30 Bach Dang, Chanh Nghia',
     '00000000-0000-0000-0000-000000000042', 10.973400, 106.658800,
     '00000000-0000-0000-0000-000000000053', '00000000-0000-0000-0000-000000000061', '00000000-0000-0000-0000-000000000073'),
    ('00000000-0000-0000-0000-000000000095', 'KH005', 'Tap hoa Hong Phat',
     '00000000-0000-0000-0000-000000000010', '0281234005', '77 Le Hong Phong, Phu Hoa',
     '00000000-0000-0000-0000-000000000041', 10.983700, 106.647200,
     '00000000-0000-0000-0000-000000000052', '00000000-0000-0000-0000-000000000061', '00000000-0000-0000-0000-000000000071'),
    ('00000000-0000-0000-0000-000000000096', 'KH006', 'Tap hoa Tuoi Tre',
     '00000000-0000-0000-0000-000000000010', '0281234006', '5 Hung Vuong, Lai Thieu',
     '00000000-0000-0000-0000-000000000043', 10.905100, 106.694700,
     '00000000-0000-0000-0000-000000000053', '00000000-0000-0000-0000-000000000061', '00000000-0000-0000-0000-000000000071'),
    ('00000000-0000-0000-0000-000000000097', 'KH007', 'Sieu thi mini Lai Thieu',
     '00000000-0000-0000-0000-000000000010', '0281234007', '210 Nguyen Van Tiet, Lai Thieu',
     '00000000-0000-0000-0000-000000000043', 10.902800, 106.698300,
     '00000000-0000-0000-0000-000000000051', '00000000-0000-0000-0000-000000000062', '00000000-0000-0000-0000-000000000072'),
    ('00000000-0000-0000-0000-000000000098', 'KH008', 'Tap hoa Kim Ngan',
     '00000000-0000-0000-0000-000000000010', '0281234008', '19 Tran Hung Dao, Phu Hoa',
     '00000000-0000-0000-0000-000000000041', 10.986200, 106.653900,
     '00000000-0000-0000-0000-000000000052', '00000000-0000-0000-0000-000000000061', '00000000-0000-0000-0000-000000000071')
on conflict (id) do nothing;

-- -----------------------------------------------------------------------------
-- Route: nvbh01 covers Thu Dau Mot Mon/Wed/Fri, Lai Thieu Tue/Thu/Sat
-- -----------------------------------------------------------------------------
insert into sales_route (id, code, name, branch_id, salesperson_id) values
    ('00000000-0000-0000-0000-0000000000a1', 'R01', 'Tuyen Thu Dau Mot',
     '00000000-0000-0000-0000-000000000010', '00000000-0000-0000-0000-000000000081')
on conflict (id) do nothing;

insert into route_customer (sales_route_id, customer_id, visit_order, visit_weekdays) values
    ('00000000-0000-0000-0000-0000000000a1', '00000000-0000-0000-0000-000000000091', 1, '{1,3,5}'),
    ('00000000-0000-0000-0000-0000000000a1', '00000000-0000-0000-0000-000000000092', 2, '{1,3,5}'),
    ('00000000-0000-0000-0000-0000000000a1', '00000000-0000-0000-0000-000000000093', 3, '{1,3,5}'),
    ('00000000-0000-0000-0000-0000000000a1', '00000000-0000-0000-0000-000000000094', 4, '{1,3,5}'),
    ('00000000-0000-0000-0000-0000000000a1', '00000000-0000-0000-0000-000000000095', 5, '{1,3,5}'),
    ('00000000-0000-0000-0000-0000000000a1', '00000000-0000-0000-0000-000000000098', 6, '{1,3,5}'),
    ('00000000-0000-0000-0000-0000000000a1', '00000000-0000-0000-0000-000000000096', 7, '{2,4,6}'),
    ('00000000-0000-0000-0000-0000000000a1', '00000000-0000-0000-0000-000000000097', 8, '{2,4,6}')
on conflict (sales_route_id, customer_id) do nothing;

-- -----------------------------------------------------------------------------
-- Reason codes
-- -----------------------------------------------------------------------------
insert into reason_code (code, name, kind) values
    ('NO_STOCK_NEED', 'Cua hang con hang',         'no_order'),
    ('NO_MONEY',      'Chua co von nhap',          'no_order'),
    ('OWNER_ABSENT',  'Chu cua hang khong co mat', 'no_order'),
    ('PRICE',         'Che gia cao',               'no_order'),
    ('CLOSED_TEMP',   'Dong cua tam thoi',         'outlet_closed'),
    ('CLOSED_PERM',   'Nghi kinh doanh',           'outlet_closed'),
    ('GPS_FAR',       'Ngoai ban kinh cho phep',   'gps_out_of_range'),
    ('GPS_WEAK',      'Tin hieu GPS yeu',          'gps_low_accuracy'),
    ('GPS_OFF',       'Khong lay duoc vi tri',     'gps_unavailable'),
    ('NO_CAMERA',     'Khong chup duoc anh',       'photo_skipped')
on conflict (code) do nothing;

-- -----------------------------------------------------------------------------
-- App settings — the knobs the legacy client read from PPC_Setting
-- -----------------------------------------------------------------------------
insert into app_setting (key, value, description) values
    ('gps_checkin_radius_m',    '100',   'Max metres from the outlet to allow check-in'),
    ('gps_max_accuracy_m',      '50',    'Reject a fix worse than this'),
    ('gps_branch_radius_m',     '200',   'Max metres from branch for clock-in'),
    ('checkin_photo_required',  'true',  'Require a photo at outlet check-in'),
    ('checkout_photo_required', 'true',  'Require a photo at outlet check-out'),
    ('checkin_late_after',      '08:30', 'Clock-in later than this is flagged'),
    ('allow_reason_when_far',   'true',  'Offer a reason code instead of blocking check-in'),
    ('default_language',        'vi',    'Fallback language code'),
    ('require_stock_before_order', 'true',
     'Rep must count outlet stock before the order step opens')
on conflict (key) do nothing;

-- -----------------------------------------------------------------------------
-- In-call workflow — server-driven, as in the legacy PPC_SalesStep
-- -----------------------------------------------------------------------------
insert into sales_step (form_id, step, title_key, is_required, needs_visit, config) values
    ('outside_checking', 1, 'step_outside_checking', true,  true, '{"note_min_length": 10}'),
    ('stock_outlet',     2, 'step_stock_outlet',     false, true, '{}'),
    ('take_order',       3, 'step_take_order',       false, true, '{}'),
    ('display_remark',   4, 'step_display_remark',   false, true, '{"photo_min": 1}'),
    ('posm_status',      5, 'step_posm_status',      false, true, '{}'),
    ('market_info',      6, 'step_market_info',      false, true, '{}'),
    ('feedback',         7, 'step_feedback',         false, true, '{"allow_audio": true, "note_min_length": 5}')
-- Converges rather than inserting once. `do nothing` is not the same as idempotent:
-- these rows were seeded before note_min_length existed, so re-running the seed left
-- the old empty config in place and the app read a minimum of nothing. Found by
-- opening the step on a device and seeing it ask for 1 character instead of 10.
--
-- Overwriting is right for the workflow definition specifically: it is the data the
-- app's behaviour keys off, and a dev seed exists to produce a known state.
on conflict (form_id) do update set
    step        = excluded.step,
    title_key   = excluded.title_key,
    is_required = excluded.is_required,
    needs_visit = excluded.needs_visit,
    config      = excluded.config;

-- -----------------------------------------------------------------------------
-- Translations
-- -----------------------------------------------------------------------------
insert into translation (lang_code, key, value) values
    ('vi', 'app_name',              'eSales SFA'),
    ('vi', 'login_title',           'Dang nhap'),
    ('vi', 'login_username',        'Ten dang nhap'),
    ('vi', 'login_password',        'Mat khau'),
    ('vi', 'login_submit',          'Dang nhap'),
    ('vi', 'route_title',           'Tuyen hom nay'),
    ('vi', 'route_empty',           'Khong co khach hang nao trong tuyen hom nay'),
    ('vi', 'checkin_title',         'Check-in'),
    ('vi', 'checkin_submit',        'Check-in'),
    ('vi', 'checkout_submit',       'Check-out'),
    ('vi', 'checkin_too_far',       'Ban dang o ngoai ban kinh cho phep'),
    ('vi', 'checkin_queued',        'Da ghi nhan, se dong bo khi co mang'),
    ('vi', 'step_outside_checking', 'Kiem tra ben ngoai'),
    ('vi', 'step_stock_outlet',     'Ton kho cua hang'),
    ('vi', 'step_take_order',       'Dat hang'),
    ('vi', 'step_display_remark',   'Cham trung bay'),
    ('vi', 'step_posm_status',      'POSM'),
    ('vi', 'step_market_info',      'Thong tin thi truong'),
    ('vi', 'step_feedback',         'Phan hoi khach hang'),
    ('en', 'app_name',              'eSales SFA'),
    ('en', 'login_title',           'Sign in'),
    ('en', 'login_username',        'Username'),
    ('en', 'login_password',        'Password'),
    ('en', 'login_submit',          'Sign in'),
    ('en', 'route_title',           'Today''s route'),
    ('en', 'route_empty',           'No customers scheduled today'),
    ('en', 'checkin_title',         'Check in'),
    ('en', 'checkin_submit',        'Check in'),
    ('en', 'checkout_submit',       'Check out'),
    ('en', 'checkin_too_far',       'You are outside the allowed radius'),
    ('en', 'checkin_queued',        'Saved - will sync when back online'),
    ('en', 'step_outside_checking', 'Outside check'),
    ('en', 'step_stock_outlet',     'Outlet stock'),
    ('en', 'step_take_order',       'Take order'),
    ('en', 'step_display_remark',   'Display audit'),
    ('en', 'step_posm_status',      'POSM'),
    ('en', 'step_market_info',      'Market info'),
    ('en', 'step_feedback',         'Customer feedback')
on conflict (lang_code, key) do nothing;

-- -----------------------------------------------------------------------------
-- Units of measure
--
-- Conversion rates below are deliberately varied (24, 36, 12, 100, 60, 4, 72):
-- a catalogue where every case held 24 would let a wrong-but-plausible total
-- pass unnoticed in testing.
-- -----------------------------------------------------------------------------
insert into uom (code, name) values
    ('PCS',  'Le'),
    ('PACK', 'Lock'),
    ('CASE', 'Thung')
on conflict (code) do nothing;

-- -----------------------------------------------------------------------------
-- Product categories                                     (uuid scheme: ..bxx)
-- -----------------------------------------------------------------------------
insert into product_category (id, code, name, sort_order) values
    ('00000000-0000-0000-0000-000000000b01', 'NGK', 'Nuoc giai khat',    1),
    ('00000000-0000-0000-0000-000000000b02', 'BK',  'Banh keo',          2),
    ('00000000-0000-0000-0000-000000000b03', 'GD',  'Hoa pham gia dung', 3)
on conflict (id) do nothing;

-- -----------------------------------------------------------------------------
-- Products                                               (uuid scheme: ..cxx)
--
-- vat_basis_points: 1000 = 10%, 800 = 8%. Both appear so a mixed-VAT order is
-- reachable without editing data.
-- -----------------------------------------------------------------------------
insert into product (id, code, name, category_id, base_uom, vat_basis_points) values
    ('00000000-0000-0000-0000-000000000c01', 'NGK001', 'Nuoc ngot Coca-Cola 330ml',
     '00000000-0000-0000-0000-000000000b01', 'PCS', 1000),
    ('00000000-0000-0000-0000-000000000c02', 'NGK002', 'Nuoc ngot Pepsi 330ml',
     '00000000-0000-0000-0000-000000000b01', 'PCS', 1000),
    ('00000000-0000-0000-0000-000000000c03', 'NGK003', 'Nuoc suoi Aquafina 500ml',
     '00000000-0000-0000-0000-000000000b01', 'PCS',  800),
    ('00000000-0000-0000-0000-000000000c04', 'NGK004', 'Tra xanh Khong Do 455ml',
     '00000000-0000-0000-0000-000000000b01', 'PCS', 1000),
    ('00000000-0000-0000-0000-000000000c05', 'NGK005', 'Bia Saigon Lager 330ml',
     '00000000-0000-0000-0000-000000000b01', 'PCS', 1000),
    ('00000000-0000-0000-0000-000000000c06', 'BK001', 'Banh Oreo goi 119g',
     '00000000-0000-0000-0000-000000000b02', 'PCS',  800),
    ('00000000-0000-0000-0000-000000000c07', 'BK002', 'Banh Cosy hop 336g',
     '00000000-0000-0000-0000-000000000b02', 'PCS',  800),
    ('00000000-0000-0000-0000-000000000c08', 'BK003', 'Keo Alpenliebe goi 105g',
     '00000000-0000-0000-0000-000000000b02', 'PCS',  800),
    ('00000000-0000-0000-0000-000000000c09', 'BK004', 'Snack Oishi tom cay 40g',
     '00000000-0000-0000-0000-000000000b02', 'PCS',  800),
    ('00000000-0000-0000-0000-000000000c10', 'GD001', 'Nuoc giat Omo Matic 2.7kg',
     '00000000-0000-0000-0000-000000000b03', 'PCS', 1000),
    ('00000000-0000-0000-0000-000000000c11', 'GD002', 'Nuoc rua bat Sunlight 750ml',
     '00000000-0000-0000-0000-000000000b03', 'PCS', 1000),
    ('00000000-0000-0000-0000-000000000c12', 'GD003', 'Kem danh rang P/S 180g',
     '00000000-0000-0000-0000-000000000b03', 'PCS', 1000)
on conflict (id) do nothing;

-- -----------------------------------------------------------------------------
-- Sale units per product
--
-- Every product carries a row for its own base unit at rate 1, so the client
-- never has to special-case the base unit. Ids are generated here: the
-- (product_id, uom_code) constraint is what makes re-seeding idempotent.
-- -----------------------------------------------------------------------------
insert into product_uom (product_id, uom_code, conversion_rate, is_default_sale, sort_order)
values
    ('00000000-0000-0000-0000-000000000c01', 'PCS',    1, false, 1),
    ('00000000-0000-0000-0000-000000000c01', 'CASE',  24, true,  2),
    ('00000000-0000-0000-0000-000000000c02', 'PCS',    1, false, 1),
    ('00000000-0000-0000-0000-000000000c02', 'CASE',  24, true,  2),
    ('00000000-0000-0000-0000-000000000c03', 'PCS',    1, false, 1),
    ('00000000-0000-0000-0000-000000000c03', 'CASE',  24, true,  2),
    ('00000000-0000-0000-0000-000000000c04', 'PCS',    1, false, 1),
    ('00000000-0000-0000-0000-000000000c04', 'CASE',  24, true,  2),
    ('00000000-0000-0000-0000-000000000c05', 'PCS',    1, false, 1),
    ('00000000-0000-0000-0000-000000000c05', 'CASE',  24, true,  2),
    ('00000000-0000-0000-0000-000000000c06', 'PCS',    1, false, 1),
    ('00000000-0000-0000-0000-000000000c06', 'CASE',  36, true,  2),
    ('00000000-0000-0000-0000-000000000c07', 'PCS',    1, false, 1),
    ('00000000-0000-0000-0000-000000000c07', 'CASE',  12, true,  2),
    -- Three sale units, so the unit picker is exercised by real data.
    ('00000000-0000-0000-0000-000000000c08', 'PCS',    1, false, 1),
    ('00000000-0000-0000-0000-000000000c08', 'PACK',  10, false, 2),
    ('00000000-0000-0000-0000-000000000c08', 'CASE', 100, true,  3),
    ('00000000-0000-0000-0000-000000000c09', 'PCS',    1, false, 1),
    ('00000000-0000-0000-0000-000000000c09', 'PACK',   6, false, 2),
    ('00000000-0000-0000-0000-000000000c09', 'CASE',  60, true,  3),
    -- Sold loose: a 2.7 kg bag is not a case item, so the base unit is default.
    ('00000000-0000-0000-0000-000000000c10', 'PCS',    1, true,  1),
    ('00000000-0000-0000-0000-000000000c10', 'CASE',   4, false, 2),
    ('00000000-0000-0000-0000-000000000c11', 'PCS',    1, false, 1),
    ('00000000-0000-0000-0000-000000000c11', 'CASE',  12, true,  2),
    ('00000000-0000-0000-0000-000000000c12', 'PCS',    1, false, 1),
    ('00000000-0000-0000-0000-000000000c12', 'CASE',  72, true,  2)
on conflict (product_id, uom_code) do nothing;

-- -----------------------------------------------------------------------------
-- List prices — class_id null is what every customer pays unless overridden.
--
-- Case prices sit below unit price x conversion rate, as they do in the trade.
-- -----------------------------------------------------------------------------
insert into price_list (product_id, uom_code, class_id, price, from_date) values
    ('00000000-0000-0000-0000-000000000c01', 'PCS',  null,   10000, date '2026-01-01'),
    ('00000000-0000-0000-0000-000000000c01', 'CASE', null,  228000, date '2026-01-01'),
    ('00000000-0000-0000-0000-000000000c02', 'PCS',  null,    9500, date '2026-01-01'),
    ('00000000-0000-0000-0000-000000000c02', 'CASE', null,  216000, date '2026-01-01'),
    ('00000000-0000-0000-0000-000000000c03', 'PCS',  null,    5000, date '2026-01-01'),
    ('00000000-0000-0000-0000-000000000c03', 'CASE', null,  112000, date '2026-01-01'),
    ('00000000-0000-0000-0000-000000000c04', 'PCS',  null,   10000, date '2026-01-01'),
    ('00000000-0000-0000-0000-000000000c04', 'CASE', null,  232000, date '2026-01-01'),
    ('00000000-0000-0000-0000-000000000c05', 'PCS',  null,   13000, date '2026-01-01'),
    ('00000000-0000-0000-0000-000000000c05', 'CASE', null,  300000, date '2026-01-01'),
    ('00000000-0000-0000-0000-000000000c06', 'PCS',  null,   15000, date '2026-01-01'),
    ('00000000-0000-0000-0000-000000000c06', 'CASE', null,  520000, date '2026-01-01'),
    ('00000000-0000-0000-0000-000000000c07', 'PCS',  null,   42000, date '2026-01-01'),
    ('00000000-0000-0000-0000-000000000c07', 'CASE', null,  495000, date '2026-01-01'),
    ('00000000-0000-0000-0000-000000000c08', 'PCS',  null,   12000, date '2026-01-01'),
    ('00000000-0000-0000-0000-000000000c08', 'PACK', null,  115000, date '2026-01-01'),
    ('00000000-0000-0000-0000-000000000c08', 'CASE', null, 1130000, date '2026-01-01'),
    ('00000000-0000-0000-0000-000000000c09', 'PCS',  null,    5000, date '2026-01-01'),
    ('00000000-0000-0000-0000-000000000c09', 'PACK', null,   29000, date '2026-01-01'),
    ('00000000-0000-0000-0000-000000000c09', 'CASE', null,  285000, date '2026-01-01'),
    ('00000000-0000-0000-0000-000000000c10', 'PCS',  null,  175000, date '2026-01-01'),
    ('00000000-0000-0000-0000-000000000c10', 'CASE', null,  690000, date '2026-01-01'),
    ('00000000-0000-0000-0000-000000000c11', 'PCS',  null,   32000, date '2026-01-01'),
    ('00000000-0000-0000-0000-000000000c11', 'CASE', null,  375000, date '2026-01-01'),
    ('00000000-0000-0000-0000-000000000c12', 'PCS',  null,   28000, date '2026-01-01'),
    ('00000000-0000-0000-0000-000000000c12', 'CASE', null, 1980000, date '2026-01-01')
on conflict (product_id, uom_code, from_date) where class_id is null do nothing;

-- Class A buys volume and pays less for it. These two rows exist so the price
-- lookup's class-over-list fallback is exercised by the seed and not only by
-- unit tests. Customer KH001 is class A.
insert into price_list (product_id, uom_code, class_id, price, from_date) values
    ('00000000-0000-0000-0000-000000000c01', 'CASE',
     '00000000-0000-0000-0000-000000000051', 222000, date '2026-01-01'),
    ('00000000-0000-0000-0000-000000000c05', 'CASE',
     '00000000-0000-0000-0000-000000000051', 294000, date '2026-01-01')
on conflict (product_id, uom_code, class_id, from_date) where class_id is not null do nothing;

-- -----------------------------------------------------------------------------
-- Must-stock lists                                        (uuid scheme: ..fxx)
--
-- Three lists on purpose, so the resolution rule is exercised by data rather than
-- only by unit tests:
--   f01  no channel, no shop type  -> the national core list, applies to everyone
--   f02  General Trade only        -> adds SKUs, and demands more Coca than f01
--   f03  Sieu thi mini only        -> scoped by shop type alone, not channel
--
-- KH001 is GT + Tap hoa, so it resolves to f01 + f02 with Coca at f02's stricter
-- 48. KH003 is MT + Sieu thi mini, so it resolves to f01 + f03 and never sees
-- f02's additions.
-- -----------------------------------------------------------------------------
insert into msl (id, code, name, channel_id, shop_type_id, from_date) values
    ('00000000-0000-0000-0000-000000000f01', 'CORE', 'Danh muc bat buoc toan quoc',
     null, null, date '2026-01-01'),
    ('00000000-0000-0000-0000-000000000f02', 'GT', 'Bo sung kenh General Trade',
     '00000000-0000-0000-0000-000000000061', null, date '2026-01-01'),
    ('00000000-0000-0000-0000-000000000f03', 'SM', 'Bo sung sieu thi mini',
     null, '00000000-0000-0000-0000-000000000072', date '2026-01-01')
on conflict (id) do nothing;

-- min_base_qty is in base units. Coca's base unit is PCS at 24 to the case, so
-- 48 means the shelf should hold two cases' worth.
insert into msl_item (msl_id, product_id, min_base_qty) values
    -- CORE
    ('00000000-0000-0000-0000-000000000f01', '00000000-0000-0000-0000-000000000c01', 24),
    ('00000000-0000-0000-0000-000000000f01', '00000000-0000-0000-0000-000000000c02', 24),
    ('00000000-0000-0000-0000-000000000f01', '00000000-0000-0000-0000-000000000c03', 48),
    ('00000000-0000-0000-0000-000000000f01', '00000000-0000-0000-0000-000000000c06', 36),
    -- GT: overlaps CORE on Coca with a stricter figure, which the union resolves
    -- to 48 rather than picking one list and discarding the other.
    ('00000000-0000-0000-0000-000000000f02', '00000000-0000-0000-0000-000000000c01', 48),
    ('00000000-0000-0000-0000-000000000f02', '00000000-0000-0000-0000-000000000c04', 24),
    ('00000000-0000-0000-0000-000000000f02', '00000000-0000-0000-0000-000000000c09', 60),
    -- SM
    ('00000000-0000-0000-0000-000000000f03', '00000000-0000-0000-0000-000000000c12', 72)
on conflict (msl_id, product_id) do nothing;

-- -----------------------------------------------------------------------------
-- Questionnaires                                          (uuid scheme: ..gxx)
--
-- Two survey types, each naming the workflow step it belongs to. Between them they
-- cover every answer type the client renders, so the survey screen and the scoring
-- are exercised by data rather than only by unit tests.
--
-- POSM is scored and has a pass threshold: 12 of an achievable 18.
--   yes_no  10 (2 questions worth 5)
--   single   5 (best option)
--   multi    3 (2 options at 2 and 1)
-- MARKET_INFO is informational, so every score is zero and pass_score is zero —
-- it always passes, which is what an information-gathering step should do.
-- -----------------------------------------------------------------------------
insert into survey_type (id, code, name, form_id, pass_score) values
    ('00000000-0000-0000-0000-000000000e01', 'POSM', 'Kiem tra POSM',
     'posm_status', 12),
    ('00000000-0000-0000-0000-000000000e02', 'MKTINFO', 'Thong tin thi truong',
     'market_info', 0)
on conflict (id) do nothing;

insert into survey_question_group (id, survey_type_id, name, sort_order) values
    ('00000000-0000-0000-0000-000000000e11', '00000000-0000-0000-0000-000000000e01',
     'Hien dien POSM', 1),
    ('00000000-0000-0000-0000-000000000e12', '00000000-0000-0000-0000-000000000e01',
     'Chat luong trung bay', 2),
    ('00000000-0000-0000-0000-000000000e13', '00000000-0000-0000-0000-000000000e02',
     'Doi thu canh tranh', 1),
    ('00000000-0000-0000-0000-000000000e14', '00000000-0000-0000-0000-000000000e02',
     'Ghi nhan khac', 2)
on conflict (id) do nothing;

insert into survey_question
    (id, group_id, code, content, answer_type, is_required, score, sort_order) values
    -- POSM: two yes_no worth 5 each
    ('00000000-0000-0000-0000-000000000e21', '00000000-0000-0000-0000-000000000e11',
     'POSTER', 'Co poster treo dung vi tri?', 'yes_no', true, 5, 1),
    ('00000000-0000-0000-0000-000000000e22', '00000000-0000-0000-0000-000000000e11',
     'KE_RIENG', 'Co ke trung bay rieng cua hang?', 'yes_no', true, 5, 2),
    -- single: score comes from the chosen option, so the question score is 0
    ('00000000-0000-0000-0000-000000000e23', '00000000-0000-0000-0000-000000000e12',
     'VI_TRI', 'Vi tri trung bay trong cua hang', 'single', true, 0, 1),
    -- multi: score is the sum of chosen options
    ('00000000-0000-0000-0000-000000000e24', '00000000-0000-0000-0000-000000000e12',
     'VAN_DE', 'Cac van de ghi nhan (chon nhieu)', 'multi', false, 0, 2),
    -- MARKET_INFO: informational, every score 0
    ('00000000-0000-0000-0000-000000000e25', '00000000-0000-0000-0000-000000000e13',
     'DT_GIA', 'Gia ban cua doi thu (dong)', 'number', true, 0, 1),
    ('00000000-0000-0000-0000-000000000e26', '00000000-0000-0000-0000-000000000e13',
     'DT_KM', 'Doi thu dang co chuong trinh khuyen mai?', 'yes_no', true, 0, 2),
    ('00000000-0000-0000-0000-000000000e27', '00000000-0000-0000-0000-000000000e14',
     'GHI_CHU', 'Ghi nhan tinh hinh thi truong', 'text', false, 0, 1)
on conflict (id) do nothing;

insert into survey_question_option
    (id, question_id, code, content, score, sort_order) values
    -- VI_TRI: best is 5, which is what max_score counts for a single question
    ('00000000-0000-0000-0000-000000000e31', '00000000-0000-0000-0000-000000000e23',
     'QUAY', 'Ngay quay thu ngan', 5, 1),
    ('00000000-0000-0000-0000-000000000e32', '00000000-0000-0000-0000-000000000e23',
     'LOI_DI', 'Loi di chinh', 3, 2),
    ('00000000-0000-0000-0000-000000000e33', '00000000-0000-0000-0000-000000000e23',
     'GOC', 'Goc cua hang', 1, 3),
    -- VAN_DE: multi, so max_score counts all three (2 + 1 + 0 = 3)
    ('00000000-0000-0000-0000-000000000e34', '00000000-0000-0000-0000-000000000e24',
     'SACH', 'Ke sach se, hang xep ngay', 2, 1),
    ('00000000-0000-0000-0000-000000000e35', '00000000-0000-0000-0000-000000000e24',
     'GIA_DUNG', 'Bang gia dung quy dinh', 1, 2),
    ('00000000-0000-0000-0000-000000000e36', '00000000-0000-0000-0000-000000000e24',
     'HET_HAN', 'Co hang gan het han tren ke', 0, 3)
on conflict (id) do nothing;
