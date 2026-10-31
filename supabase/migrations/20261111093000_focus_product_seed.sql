-- Fake data: focus products running now (Oct 15 – Nov 30, 2026), so the rep's
-- "Trọng tâm bán hàng" and the supervisor's team view both have something to
-- show. Targets are per rep. The seeded ones from August had all expired.
insert into focus_product (id, product_id, branch_id, from_date, to_date, priority, target_base_qty, note, is_active)
select v.id::uuid, p.id, null, date '2026-10-15', date '2026-11-30', v.priority, v.target, v.note, true
from (values
    ('00000000-0000-0000-0000-0000000000d7', 'NGK001', 1, 2400, 'Đẩy mạnh mùa lễ hội cuối năm'),
    ('00000000-0000-0000-0000-0000000000d8', 'NGK005', 2, 2000, null),
    ('00000000-0000-0000-0000-0000000000d9', 'NGK002', 3, 600,  'Sản phẩm mới phủ điểm bán')
) as v(id, code, priority, target, note)
join product p on p.code = v.code
on conflict (id) do nothing;
