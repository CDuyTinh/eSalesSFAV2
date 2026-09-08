-- =============================================================================
-- Dữ liệu mẫu: chuẩn của từng mức trưng bày
--
-- Enough for the screen to be worked against: the drinks programme wants named
-- products at each of its three levels, with the count rising level by level, and
-- the basic shelf programme wants one facing of each staple.
--
-- The reference photographs point at picsum.photos, as the POSM seed does. They
-- are stand-ins for whatever head office actually publishes — what matters here
-- is that several arrive, in order, and the screen shows them.
-- =============================================================================

insert into display_program_level_image (level_id, image_url, caption, sort_order)
values
    ('00000000-0000-0000-0000-0000000e0001',
     'https://picsum.photos/seed/tb2609-m1-1/640/480', 'Nhìn chính diện', 1),
    ('00000000-0000-0000-0000-0000000e0001',
     'https://picsum.photos/seed/tb2609-m1-2/640/480', 'Nhìn nghiêng', 2),
    ('00000000-0000-0000-0000-0000000e0002',
     'https://picsum.photos/seed/tb2609-m2-1/640/480', 'Nhìn chính diện', 1),
    ('00000000-0000-0000-0000-0000000e0002',
     'https://picsum.photos/seed/tb2609-m2-2/640/480', 'Khối liền mạch', 2),
    ('00000000-0000-0000-0000-0000000e0002',
     'https://picsum.photos/seed/tb2609-m2-3/640/480', 'Nhãn quay ra ngoài', 3),
    ('00000000-0000-0000-0000-0000000e0003',
     'https://picsum.photos/seed/tb2609-m3-1/640/480', 'Nhìn chính diện', 1),
    ('00000000-0000-0000-0000-0000000e0004',
     'https://picsum.photos/seed/tbq0926-cb-1/640/480', 'Kệ đạt chuẩn', 1)
on conflict (level_id, image_url) do nothing;

-- Mức 1: sáu mặt chia cho hai nhãn chủ lực.
insert into display_program_level_item (level_id, product_id, qty, is_required, sort_order)
select '00000000-0000-0000-0000-0000000e0001', p.id, x.qty, x.required, x.sort_order
from (values
    ('NGK001', 4, true,  1),
    ('NGK003', 2, true,  2)
) as x(code, qty, required, sort_order)
join product p on p.code = x.code
on conflict (level_id, product_id) do nothing;

-- Mức 2: mười hai mặt, thêm trà xanh, và một dòng không bắt buộc.
insert into display_program_level_item (level_id, product_id, qty, is_required, sort_order)
select '00000000-0000-0000-0000-0000000e0002', p.id, x.qty, x.required, x.sort_order
from (values
    ('NGK001', 5, true,  1),
    ('NGK002', 3, true,  2),
    ('NGK003', 2, true,  3),
    ('NGK004', 2, false, 4)
) as x(code, qty, required, sort_order)
join product p on p.code = x.code
on conflict (level_id, product_id) do nothing;

-- Mức 3: hai mươi mặt, cả dòng bia.
insert into display_program_level_item (level_id, product_id, qty, is_required, sort_order)
select '00000000-0000-0000-0000-0000000e0003', p.id, x.qty, x.required, x.sort_order
from (values
    ('NGK001', 6, true,  1),
    ('NGK002', 5, true,  2),
    ('NGK003', 4, true,  3),
    ('NGK004', 3, true,  4),
    ('NGK005', 2, false, 5)
) as x(code, qty, required, sort_order)
join product p on p.code = x.code
on conflict (level_id, product_id) do nothing;

-- Cơ bản: một mặt mỗi nhãn.
insert into display_program_level_item (level_id, product_id, qty, is_required, sort_order)
select '00000000-0000-0000-0000-0000000e0004', p.id, 1, true, x.sort_order
from (values
    ('NGK001', 1),
    ('BK001',  2),
    ('GD002',  3)
) as x(code, sort_order)
join product p on p.code = x.code
on conflict (level_id, product_id) do nothing;
