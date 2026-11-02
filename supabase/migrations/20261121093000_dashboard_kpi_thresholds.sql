-- The manager overview's colour bands for an achievement percentage (the
-- legacy DASHBOARD_KPI_GREEN / DASHBOARD_KPI_YELLOW): at or above green it is
-- green, at or above yellow it is blue, below that orange. Seeded at the values
-- the app had hard-coded, so nothing changes until head office edits them.
insert into app_setting (key, value, description)
values
    ('dashboard_kpi_green',  '100', 'Tổng quan quản lý: % đạt chỉ tiêu từ mức này trở lên tô xanh lá'),
    ('dashboard_kpi_yellow', '70',  'Tổng quan quản lý: % đạt chỉ tiêu từ mức này trở lên tô xanh dương, dưới mức này tô cam')
on conflict (key) do nothing;
