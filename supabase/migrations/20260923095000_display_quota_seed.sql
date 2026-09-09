-- =============================================================================
-- Dữ liệu mẫu: cửa sổ đăng ký và suất của nhân viên
--
-- TB2609 takes registrations for another three weeks and gives the demo rep a
-- handful of slots, tighter at the richer levels — which is the shape of every
-- real allocation and the only one that exercises the "hết suất" path.
-- =============================================================================

update display_program
set regis_from_date = current_date - 30,
    regis_to_date   = current_date + 21
where code = 'TB2609';

insert into display_program_quota (level_id, salesperson_id, slots, used)
select lv.id, s.id, x.slots, 0
from (values
    ('M1', 5),
    ('M2', 2),
    -- Nothing at the richest level, so the screen has a "hết suất" to show.
    ('M3', 0)
) as x(code, slots)
join display_program_level lv on lv.code = x.code
join display_program p on p.id = lv.program_id and p.code = 'TB2609'
join salesperson s on s.code = 'nvbh01'
on conflict (level_id, salesperson_id) do nothing;
