-- =============================================================================
-- Dữ liệu mẫu: việc chờ gsbh01 duyệt
--
-- Display, loyalty and POSM already have pending sign-ups from earlier seeds.
-- Leave and field-registered outlets had none, so the approval screens would
-- open empty on the two most common cases. One approved leave is included so
-- the "Đã duyệt" chip has something to show.
-- =============================================================================

do $$
declare
    v_branch uuid := '00000000-0000-0000-0000-000000000010';
    v_nam    uuid := '00000000-0000-0000-0000-000000000081';  -- nvbh01
    v_hoa    uuid := '00000000-0000-0000-0000-000000000082';  -- nvbh02
    v_boss   uuid := '00000000-0000-0000-0000-000000000083';  -- gsbh01
    v_ward   uuid := (select id from ward order by code limit 1);
begin
    insert into leave_request (salesperson_id, branch_id, leave_type_id, from_date, to_date, reason)
    values
        (v_nam, v_branch, '00000000-0000-0000-0000-0000000000f8',
         current_date + 7, current_date + 8, 'Về quê dự đám cưới em gái'),
        (v_hoa, v_branch, '00000000-0000-0000-0000-0000000000f9',
         current_date + 1, current_date + 1, 'Khám sức khoẻ định kỳ');

    insert into leave_request (salesperson_id, branch_id, leave_type_id, from_date, to_date,
                               reason, status, decision_note, decided_at, decided_by)
    values
        (v_nam, v_branch, '00000000-0000-0000-0000-0000000000fc',
         current_date - 20, current_date - 20, 'Việc gia đình', 'approved',
         'Đồng ý', now() - interval '21 days', v_boss);

    -- One statement per outlet: the code generator reads the current maximum,
    -- so two calls inside a single INSERT would both see the same one.
    insert into customer (code, name, branch_id, phone, address, ward_id, lat, lng,
                          approval_status, created_by_salesperson_id, registration_note)
    values (next_customer_registration_code(v_branch), 'Tạp hoá Minh Phát', v_branch,
            '0911222333', '12 Nguyễn Văn Tiết, Lái Thiêu', v_ward, 10.9050, 106.7010,
            'pending', v_nam, 'Cửa hàng mới mở, chủ muốn lấy hàng mì gói');

    insert into customer (code, name, branch_id, phone, address, ward_id, lat, lng,
                          approval_status, created_by_salesperson_id, registration_note)
    values (next_customer_registration_code(v_branch), 'Cửa hàng Bảy Hồng', v_branch,
            '0988777666', '45 Cách Mạng Tháng 8, Thủ Dầu Một', v_ward, 10.9800, 106.6550,
            'pending', v_nam, null);

    insert into customer (code, name, branch_id, phone, address, ward_id, lat, lng,
                          approval_status, created_by_salesperson_id, registration_note)
    values (next_customer_registration_code(v_branch), 'Quán Cô Ba', v_branch,
            '0977111222', '7 Yersin, Phú Cường', v_ward, 10.9810, 106.6520,
            'pending', v_hoa, 'Quán ăn sáng, lấy mì và phở gói');
end;
$$;
