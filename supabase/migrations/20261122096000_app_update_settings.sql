-- Which build of each app head office wants in use. The apps are not on a
-- store, so they cannot ask one; they read these with the rest of the settings
-- and compare their own version code:
--   below *_version_min    -> a dialog that cannot be dismissed: update to continue
--   below *_version_latest -> a dialog the user may put off
-- *_update_url is where the build is (an APK link, a store page); empty opens
-- the store page for the app's package. *_update_note is a line on what changed.
--
-- Seeded at the version both apps are on today, so nothing shows until a new
-- build is released and these are raised.
insert into app_setting (key, value, description)
values
    ('sfa_version_latest',     '1', 'SFA: versionCode bản mới nhất; app thấp hơn sẽ được gợi ý cập nhật'),
    ('sfa_version_min',        '1', 'SFA: versionCode thấp nhất còn dùng được; app thấp hơn bắt buộc cập nhật'),
    ('sfa_update_url',         '',  'SFA: link tải bản mới (APK hoặc trang cửa hàng); để trống mở trang cửa hàng'),
    ('sfa_update_note',        '',  'SFA: ghi chú ngắn về bản mới, hiện trong hộp thoại cập nhật'),
    ('manager_version_latest', '1', 'Manager: versionCode bản mới nhất; app thấp hơn sẽ được gợi ý cập nhật'),
    ('manager_version_min',    '1', 'Manager: versionCode thấp nhất còn dùng được; app thấp hơn bắt buộc cập nhật'),
    ('manager_update_url',     '',  'Manager: link tải bản mới (APK hoặc trang cửa hàng); để trống mở trang cửa hàng'),
    ('manager_update_note',    '',  'Manager: ghi chú ngắn về bản mới, hiện trong hộp thoại cập nhật')
on conflict (key) do nothing;
