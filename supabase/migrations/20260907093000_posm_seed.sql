-- =============================================================================
-- Demo POSM
--
-- Two programmes, five assets, and outlets holding different mixes of them —
-- because a seed where every shop holds the same thing is a seed that hides
-- every bug in the per-outlet join.
--
-- KH006 holds three assets across both programmes, so the step has a real list
-- to work through and the "checked 2 of 3" state is reachable. KH007 holds one,
-- and has a registration still waiting on head office. KH002 holds none, which
-- is the empty case the screen also has to answer for.
--
-- Idempotent, so re-running after a reset does not double the rows.
-- =============================================================================

insert into posm_item (id, code, name, unit_name, is_active)
values
    ('00000000-0000-0000-0000-0000000f0001', 'TL200',  'Tủ lạnh trưng bày 200L', 'Cái', true),
    ('00000000-0000-0000-0000-0000000f0002', 'KE3T',   'Kệ trưng bày 3 tầng',    'Cái', true),
    ('00000000-0000-0000-0000-0000000f0003', 'HOPDEN', 'Hộp đèn logo',           'Cái', true),
    ('00000000-0000-0000-0000-0000000f0004', 'BANGHE', 'Bộ bàn ghế nhựa',        'Bộ',  true),
    ('00000000-0000-0000-0000-0000000f0005', 'POSTER', 'Poster khổ lớn',         'Tờ',  true)
on conflict (code) do nothing;

insert into posm_program (id, code, name, from_date, to_date, branch_id, is_active)
values
    (
        '00000000-0000-0000-0000-0000000f1001',
        'POSM2026',
        'Trang bị điểm bán 2026',
        current_date - 120,
        current_date + 240,
        -- Demo branch only, so the branch column is exercised.
        '00000000-0000-0000-0000-000000000010',
        true
    ),
    (
        '00000000-0000-0000-0000-0000000f1002',
        'POSMQC',
        'Vật phẩm quảng cáo thường xuyên',
        current_date - 60,
        current_date + 300,
        -- Every branch.
        null,
        true
    )
on conflict (code) do nothing;

insert into posm_program_item (program_id, posm_item_id, max_per_customer, sort_order)
values
    ('00000000-0000-0000-0000-0000000f1001', '00000000-0000-0000-0000-0000000f0001', 1, 1),
    ('00000000-0000-0000-0000-0000000f1001', '00000000-0000-0000-0000-0000000f0002', 2, 2),
    ('00000000-0000-0000-0000-0000000f1001', '00000000-0000-0000-0000-0000000f0004', 2, 3),
    -- No ceiling on printed matter; a shop can take as many posters as it wants.
    ('00000000-0000-0000-0000-0000000f1002', '00000000-0000-0000-0000-0000000f0003', 1, 1),
    ('00000000-0000-0000-0000-0000000f1002', '00000000-0000-0000-0000-0000000f0005', 0, 2)
on conflict (program_id, posm_item_id) do nothing;

-- What is physically at each outlet. Both programmes are represented at KH006
-- on purpose: the listing groups by programme and one group would not prove it.
insert into posm_placement (customer_id, program_id, posm_item_id, qty, placed_at)
values
    -- KH006 Tap hoa Tuoi Tre
    ('00000000-0000-0000-0000-000000000096', '00000000-0000-0000-0000-0000000f1001',
     '00000000-0000-0000-0000-0000000f0001', 1, current_date - 90),
    ('00000000-0000-0000-0000-000000000096', '00000000-0000-0000-0000-0000000f1001',
     '00000000-0000-0000-0000-0000000f0002', 2, current_date - 90),
    ('00000000-0000-0000-0000-000000000096', '00000000-0000-0000-0000-0000000f1002',
     '00000000-0000-0000-0000-0000000f0003', 1, current_date - 30),

    -- KH007 Sieu thi mini Lai Thieu
    ('00000000-0000-0000-0000-000000000097', '00000000-0000-0000-0000-0000000f1002',
     '00000000-0000-0000-0000-0000000f0005', 3, current_date - 20),

    -- KH001, so the feature is not a one-outlet demo
    ('00000000-0000-0000-0000-000000000091', '00000000-0000-0000-0000-0000000f1001',
     '00000000-0000-0000-0000-0000000f0004', 2, current_date - 45)
on conflict (customer_id, program_id, posm_item_id) do nothing;

-- Registrations, in all three states the screen has to render.
insert into posm_registration (program_id, posm_item_id, customer_id,
                               regis_qty, approved_qty, delivered_qty,
                               status, registered_at)
values
    -- Delivered in full: this is the fridge KH006 is holding.
    ('00000000-0000-0000-0000-0000000f1001', '00000000-0000-0000-0000-0000000f0001',
     '00000000-0000-0000-0000-000000000096', 1, 1, 1, 'approved', current_date - 100),
    -- Approved but only half delivered — the case the rep has to chase.
    ('00000000-0000-0000-0000-0000000f1001', '00000000-0000-0000-0000-0000000f0002',
     '00000000-0000-0000-0000-000000000096', 4, 2, 2, 'approved', current_date - 100),
    -- Still with head office.
    ('00000000-0000-0000-0000-0000000f1001', '00000000-0000-0000-0000-0000000f0004',
     '00000000-0000-0000-0000-000000000096', 2, 0, 0, 'pending',  current_date - 5),
    -- Turned down.
    ('00000000-0000-0000-0000-0000000f1002', '00000000-0000-0000-0000-0000000f0003',
     '00000000-0000-0000-0000-000000000097', 2, 0, 0, 'rejected', current_date - 40),
    ('00000000-0000-0000-0000-0000000f1002', '00000000-0000-0000-0000-0000000f0005',
     '00000000-0000-0000-0000-000000000097', 3, 3, 3, 'approved', current_date - 25)
on conflict (program_id, posm_item_id, customer_id) do nothing;
