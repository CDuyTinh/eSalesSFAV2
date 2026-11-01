-- The refusal reasons offered on the manager's decision dialog. Kept general:
-- one list serves every kind of approval, and anything more particular is what
-- the free text is for. The digit in each code is the order they are shown in.
insert into reason_code (code, name, kind) values
    ('REJ_1_INFO',   'Thiếu hoặc sai thông tin',                    'approval_reject'),
    ('REJ_2_PHOTO',  'Hình ảnh không đạt yêu cầu',                  'approval_reject'),
    ('REJ_3_DUP',    'Trùng với khách hàng / đăng ký đã có',        'approval_reject'),
    ('REJ_4_POLICY', 'Không đúng điều kiện, chính sách chương trình', 'approval_reject'),
    ('REJ_5_SCHEDULE', 'Trùng lịch công tác, cần sắp xếp lại',        'approval_reject'),
    ('REJ_6_COVER',  'Không đủ người đi tuyến trong thời gian này', 'approval_reject')
on conflict (code) do nothing;
