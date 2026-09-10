-- =============================================================================
-- Dữ liệu mẫu: chương trình tích lũy
--
-- One programme counting money over a quarter, three bands, and the drinks the
-- demo route actually sells. One outlet already in it so the progress figure has
-- something to add up, and the rest open so the registration path has somewhere
-- to go.
-- =============================================================================

insert into loyalty_program (
    id, code, name, counts_by, from_date, to_date,
    regis_from_date, regis_to_date, branch_id, specification
)
values (
    '00000000-0000-0000-0000-0000000f0001',
    'TL-Q3',
    'Tích lũy nước giải khát quý 3',
    'amount',
    current_date - 45,
    current_date + 45,
    current_date - 45,
    current_date + 20,
    null,
    'Cộng dồn doanh số các mặt hàng nước giải khát trong kỳ. Thưởng theo tỷ lệ '
    'phần trăm trên doanh số đạt được, chi trả cuối kỳ.'
)
on conflict (code) do nothing;

insert into loyalty_program_level (
    id, program_id, code, name, target_from, target_to, reward_basis_points, sort_order
)
values
    ('00000000-0000-0000-0000-0000000f1001', '00000000-0000-0000-0000-0000000f0001',
     'B1', 'Bậc 1 - từ 5 triệu',   5000000, 10000000,  100, 1),
    ('00000000-0000-0000-0000-0000000f1002', '00000000-0000-0000-0000-0000000f0001',
     'B2', 'Bậc 2 - từ 10 triệu', 10000000, 20000000,  200, 2),
    -- Open top band: LevelTo null is the legacy's "and everything above this".
    ('00000000-0000-0000-0000-0000000f1003', '00000000-0000-0000-0000-0000000f0001',
     'B3', 'Bậc 3 - từ 20 triệu', 20000000,     null,  350, 3)
on conflict (id) do nothing;

insert into loyalty_program_item (program_id, product_id)
select '00000000-0000-0000-0000-0000000f0001', p.id
from product p
where p.code in ('NGK001', 'NGK002', 'NGK003', 'NGK004', 'NGK005')
on conflict (program_id, product_id) do nothing;

-- Slots per level, tighter at the richer bands as every real allocation is, and
-- none at all at the top so the screen has a "hết suất" to show.
insert into loyalty_program_quota (level_id, salesperson_id, slots, used)
select lv.id, s.id, x.slots, 0
from (values
    ('B1', 4),
    ('B2', 2),
    ('B3', 0)
) as x(code, slots)
join loyalty_program_level lv on lv.code = x.code
join loyalty_program p on p.id = lv.program_id and p.code = 'TL-Q3'
join salesperson s on s.code = 'nvbh01'
on conflict (level_id, salesperson_id) do nothing;

-- One outlet already signed up, approved, so the progress figure has a
-- registration to hang off and the tab shows both of its sections at once.
insert into loyalty_registration (
    program_id, level_id, customer_id, status, registered_at, portion
)
select
    '00000000-0000-0000-0000-0000000f0001',
    '00000000-0000-0000-0000-0000000f1001',
    c.id,
    'approved',
    current_date - 40,
    1
from customer c
where c.code = 'KH001'
on conflict (program_id, customer_id) do nothing;
