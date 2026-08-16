-- =============================================================================
-- Menu configuration
--
-- Which tabs the shell shows, and what sits inside the three that open a sheet
-- instead of a page. Data, not code — the same reason `sales_step` is a table.
-- A market that does not sell on credit turns off RECEIVABLE without a release.
--
-- One self-referencing table rather than two. The levels differ only in whether
-- something sits above them, and a second table would have duplicated every
-- column to say so.
-- =============================================================================

create table menu_item (
    id          uuid primary key default gen_random_uuid(),
    /** Stable identifier the client switches on, e.g. DASH_BOARD, RECEIVABLE. */
    code        text    not null unique,
    /** Null for a bottom-bar tab; set for an entry inside a tab's sheet. */
    parent_code text references menu_item (code) on delete cascade,
    title_key   text    not null,
    sort_order  integer not null default 0,
    is_active   boolean not null default true,
    created_at  timestamptz not null default now(),

    -- A child of a child would have nowhere to render: the shell has a bar and a
    -- sheet, and nothing below that.
    constraint parent_is_not_itself check (parent_code is distinct from code)
);

create index on menu_item (parent_code, sort_order);

alter table menu_item enable row level security;

create policy "reference readable by authenticated"
    on menu_item for select to authenticated using (true);

-- -----------------------------------------------------------------------------
-- Seed: the five tabs, and the entries inside the three that open a sheet.
--
-- Ordered to match the legacy app so a rep moving between them is not hunting for
-- a tab that moved.
-- -----------------------------------------------------------------------------

insert into menu_item (code, parent_code, title_key, sort_order) values
    ('DASH_BOARD',  null, 'menu_dashboard',   0),
    ('PREPARATION', null, 'menu_preparation', 1),
    ('CHECK_IN',    null, 'menu_check_in',    2),
    ('TASKS',       null, 'menu_tasks',       3),
    ('OTHER',       null, 'menu_other',       4)
on conflict (code) do nothing;

insert into menu_item (code, parent_code, title_key, sort_order) values
    ('DAILY_SALES_TARGET', 'PREPARATION', 'menu_daily_sales_target', 0),
    ('SALES_FOCUS',        'PREPARATION', 'menu_sales_focus',        1),
    ('SITE',               'PREPARATION', 'menu_site',               2),

    ('MY_TASK',      'TASKS', 'menu_my_task',      0),
    ('WORKING_PLAN', 'TASKS', 'menu_working_plan', 1),
    ('WORKING_NOTE', 'TASKS', 'menu_working_note', 2),
    ('MARKET_INFO',  'TASKS', 'menu_market_info',  3),

    ('LEAVE_APPLICATION', 'OTHER', 'menu_leave_application', 0),
    ('TRADE_REGIS',       'OTHER', 'menu_trade_regis',       1),
    ('NEW_CUSTOMER',      'OTHER', 'menu_new_customer',      2),
    ('REPORT',            'OTHER', 'menu_report',            3),
    ('RECEIVABLE',        'OTHER', 'menu_receivable',        4)
on conflict (code) do nothing;

-- -----------------------------------------------------------------------------
-- Labels. Upserted rather than inserted so a re-run corrects a wording change
-- instead of silently keeping the old one.
-- -----------------------------------------------------------------------------

insert into translation (lang_code, key, value) values
    ('vi', 'menu_dashboard',          'Tong quan'),
    ('vi', 'menu_preparation',        'Chuan bi'),
    ('vi', 'menu_check_in',           'Vieng tham'),
    ('vi', 'menu_tasks',              'Cong viec'),
    ('vi', 'menu_other',              'Khac'),
    ('vi', 'menu_daily_sales_target', 'Chi tieu ngay'),
    ('vi', 'menu_sales_focus',        'San pham trong tam'),
    ('vi', 'menu_site',               'Kho xuat hang'),
    ('vi', 'menu_my_task',            'Viec cua toi'),
    ('vi', 'menu_working_plan',       'Ke hoach lam viec'),
    ('vi', 'menu_working_note',       'Ghi chu cong viec'),
    ('vi', 'menu_market_info',        'Thong tin thi truong'),
    ('vi', 'menu_leave_application',  'Don xin nghi'),
    ('vi', 'menu_trade_regis',        'Dang ky chuong trinh'),
    ('vi', 'menu_new_customer',       'Khach hang moi'),
    ('vi', 'menu_report',             'Bao cao'),
    ('vi', 'menu_receivable',         'Cong no'),
    ('vi', 'shell_not_built',         'Chuc nang nay chua co trong ban nay'),
    ('vi', 'drawer_account',          'Thong tin tai khoan'),
    ('vi', 'drawer_refresh',          'Cap nhat du lieu'),
    ('vi', 'drawer_sign_out',         'Dang xuat'),

    ('en', 'menu_dashboard',          'Overview'),
    ('en', 'menu_preparation',        'Prepare'),
    ('en', 'menu_check_in',           'Visits'),
    ('en', 'menu_tasks',              'Work'),
    ('en', 'menu_other',              'More'),
    ('en', 'menu_daily_sales_target', 'Daily target'),
    ('en', 'menu_sales_focus',        'Focus products'),
    ('en', 'menu_site',               'Issuing site'),
    ('en', 'menu_my_task',            'My tasks'),
    ('en', 'menu_working_plan',       'Work plan'),
    ('en', 'menu_working_note',       'Work notes'),
    ('en', 'menu_market_info',        'Market info'),
    ('en', 'menu_leave_application',  'Leave request'),
    ('en', 'menu_trade_regis',        'Programme sign-up'),
    ('en', 'menu_new_customer',       'New customer'),
    ('en', 'menu_report',             'Reports'),
    ('en', 'menu_receivable',         'Receivables'),
    ('en', 'shell_not_built',         'Not available in this build'),
    ('en', 'drawer_account',          'Account'),
    ('en', 'drawer_refresh',          'Refresh data'),
    ('en', 'drawer_sign_out',         'Sign out')
on conflict (lang_code, key) do update set value = excluded.value;
