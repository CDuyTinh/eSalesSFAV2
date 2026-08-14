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
    ('default_language',        'vi',    'Fallback language code')
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
on conflict (form_id) do nothing;

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
