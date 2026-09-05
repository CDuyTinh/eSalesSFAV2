-- =============================================================================
-- Demo display programmes
--
-- Two programmes, deliberately of the two different shapes the listing has to
-- handle, because a seed that only exercises one of them is a seed that hides
-- the other one's bug:
--
--   TB2609  requires registration. Three levels. Four outlets signed up at
--           different levels, one of them still pending, so the screen has to
--           show a level that varies per outlet and a registration that head
--           office has not ruled on.
--
--   TBQ0926 does not. No registration rows at all, and every outlet on the
--           route is audited against its lowest level — the legacy's
--           CheckRegistry = 0 branch.
--
-- Dated around today so the window is open on the demo device. Idempotent, so
-- re-running it after a reset does not double the rows.
-- =============================================================================

insert into display_program (id, code, name, from_date, to_date, branch_id,
                             requires_registration, specification)
values
    (
        '00000000-0000-0000-0000-0000000d0001',
        'TB2609',
        'Trưng bày nước giải khát quý 3',
        current_date - 30,
        current_date + 60,
        -- Runs in the demo branch only, so the branch filter is exercised.
        '00000000-0000-0000-0000-000000000010',
        true,
        'Xếp thành khối liền mạch trên kệ ngang tầm mắt, nhãn quay ra ngoài, '
        'không xen lẫn sản phẩm của hãng khác.'
    ),
    (
        '00000000-0000-0000-0000-0000000d0002',
        'TBQ0926',
        'Quầy kệ sạch - trưng bày cơ bản',
        current_date - 10,
        current_date + 90,
        -- Every branch.
        null,
        false,
        'Kệ sạch, không hàng hết hạn, tối thiểu một mặt cho mỗi nhãn chủ lực.'
    )
on conflict (code) do nothing;

insert into display_program_level (id, program_id, code, name, required_faces,
                                   bonus_amount, sort_order)
values
    ('00000000-0000-0000-0000-0000000e0001', '00000000-0000-0000-0000-0000000d0001',
     'M1', 'Mức 1 - 6 mặt',  6,    300000, 1),
    ('00000000-0000-0000-0000-0000000e0002', '00000000-0000-0000-0000-0000000d0001',
     'M2', 'Mức 2 - 12 mặt', 12,   700000, 2),
    ('00000000-0000-0000-0000-0000000e0003', '00000000-0000-0000-0000-0000000d0001',
     'M3', 'Mức 3 - 20 mặt', 20,  1500000, 3),
    ('00000000-0000-0000-0000-0000000e0004', '00000000-0000-0000-0000-0000000d0002',
     'CB', 'Cơ bản - 3 mặt',  3,        0, 1)
on conflict (id) do nothing;

-- Registrations for the programme that needs them. Levels differ per outlet on
-- purpose: the screen must read the target from the registration, not from the
-- programme.
insert into display_registration (program_id, level_id, customer_id, status, registered_at)
values
    ('00000000-0000-0000-0000-0000000d0001', '00000000-0000-0000-0000-0000000e0002',
     '00000000-0000-0000-0000-000000000091', 'approved', current_date - 25),
    ('00000000-0000-0000-0000-0000000d0001', '00000000-0000-0000-0000-0000000e0003',
     '00000000-0000-0000-0000-000000000093', 'approved', current_date - 25),
    ('00000000-0000-0000-0000-0000000d0001', '00000000-0000-0000-0000-0000000e0001',
     '00000000-0000-0000-0000-000000000096', 'approved', current_date - 20),
    -- Signed up and waiting on head office. The rep still audits it.
    ('00000000-0000-0000-0000-0000000d0001', '00000000-0000-0000-0000-0000000e0002',
     '00000000-0000-0000-0000-000000000097', 'pending',  current_date - 3)
on conflict (program_id, customer_id) do nothing;
