-- =============================================================================
-- Spell the server's Vietnamese with its diacritics too
--
-- Found by running the app on a device rather than by reading it. Every string
-- the app itself draws was given its diacritics some commits ago; every string
-- the *server* supplies was not, and those sit side by side on the same screen.
-- The bottom bar read "Tong quan · Chuan bi · Vieng tham" underneath a header
-- reading "Viếng thăm", and the check-in reason list offered "Khong lay duoc vi
-- tri" under the prompt "Không lấy được vị trí. Chọn lý do để tiếp tục:".
--
-- Three sources, all of them seeded rather than typed by a user: menu and step
-- labels in `translation`, and the coded reasons in `reason_code`. The English
-- rows are left exactly as they are — they were already correct, and this app
-- serves Vietnamese reps only.
-- =============================================================================

update translation set value = case key
    when 'menu_dashboard'          then 'Tổng quan'
    when 'menu_preparation'        then 'Chuẩn bị'
    when 'menu_check_in'           then 'Viếng thăm'
    when 'menu_tasks'              then 'Công việc'
    when 'menu_other'              then 'Khác'
    when 'menu_daily_sales_target' then 'Chỉ tiêu ngày'
    when 'menu_sales_focus'        then 'Sản phẩm trọng tâm'
    when 'menu_site'               then 'Kho xuất hàng'
    when 'menu_my_task'            then 'Việc của tôi'
    when 'menu_working_plan'       then 'Kế hoạch làm việc'
    when 'menu_working_note'       then 'Ghi chú công việc'
    when 'menu_market_info'        then 'Thông tin thị trường'
    when 'menu_leave_application'  then 'Đơn xin nghỉ'
    when 'menu_trade_regis'        then 'Đăng ký chương trình'
    when 'menu_new_customer'       then 'Khách hàng mới'
    when 'menu_report'             then 'Báo cáo'
    when 'menu_receivable'         then 'Công nợ'

    when 'step_outside_checking'   then 'Kiểm tra bên ngoài'
    when 'step_stock_outlet'       then 'Tồn kho cửa hàng'
    when 'step_take_order'         then 'Đặt hàng'
    when 'step_display_remark'     then 'Chấm trưng bày'
    when 'step_feedback'           then 'Phản hồi khách hàng'
    when 'step_market_info'        then 'Thông tin thị trường'

    when 'drawer_account'          then 'Thông tin tài khoản'
    when 'drawer_refresh'          then 'Cập nhật dữ liệu'
    when 'drawer_sign_out'         then 'Đăng xuất'
    when 'shell_not_built'         then 'Chức năng này chưa có trong bản này'

    -- Carried over from before the screens were rebuilt. Nothing reads these
    -- today, but leaving half a table unaccented is how the next person
    -- concludes the convention is "sometimes".
    when 'login_title'             then 'Đăng nhập'
    when 'login_submit'            then 'Đăng nhập'
    when 'login_username'          then 'Tên đăng nhập'
    when 'login_password'          then 'Mật khẩu'
    when 'route_title'             then 'Tuyến hôm nay'
    when 'route_empty'             then 'Không có khách hàng nào trong tuyến hôm nay'
    when 'checkin_too_far'         then 'Bạn đang ở ngoài bán kính cho phép'
    when 'checkin_queued'          then 'Đã ghi nhận, sẽ đồng bộ khi có mạng'
    else value
end
where lang_code = 'vi';

-- -----------------------------------------------------------------------------
-- Reason codes, which a rep reads at the moment they are least able to guess:
-- standing outside a shop with the check-in refused.
-- -----------------------------------------------------------------------------

update reason_code set name = case code
    when 'GPS_FAR'       then 'Ngoài bán kính cho phép'
    when 'GPS_WEAK'      then 'Tín hiệu GPS yếu'
    when 'GPS_OFF'       then 'Không lấy được vị trí'
    when 'NO_CAMERA'     then 'Không chụp được ảnh'

    when 'CLOSED_TEMP'   then 'Đóng cửa tạm thời'
    when 'CLOSED_PERM'   then 'Nghỉ kinh doanh'

    when 'NO_MONEY'      then 'Chưa có vốn nhập'
    when 'NO_STOCK_NEED' then 'Cửa hàng còn hàng'
    when 'OWNER_ABSENT'  then 'Chủ cửa hàng không có mặt'
    when 'PRICE'         then 'Chê giá cao'

    when 'FB_PRICE'      then 'Giá cả'
    when 'FB_QUALITY'    then 'Chất lượng sản phẩm'
    when 'FB_DELIVERY'   then 'Giao hàng'
    when 'FB_POSM'       then 'Yêu cầu POSM / thiết bị'
    when 'FB_COMPETITOR' then 'Thông tin đối thủ'
    when 'FB_OTHER'      then 'Khác'
    else name
end;
