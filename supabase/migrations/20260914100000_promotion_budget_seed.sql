-- =============================================================================
-- Demo ngân sách
--
-- One pot, deliberately small, behind the order-level campaign: 250.000 đ for
-- the demo rep's route against a rule that gives 100.000 đ a time. Two orders
-- clear it and the third finds the campaign gone, which is the behaviour worth
-- being able to see rather than read about.
-- =============================================================================

insert into discount_budget (id, code, name, counts, alloc_by, from_date, to_date)
values (
    '00000000-0000-0000-0000-000000030001',
    'NS-DOC',
    'Ngân sách giảm giá đơn hàng',
    'amount', 'route',
    current_date - 30, current_date + 180
)
on conflict (code) do nothing;

insert into discount_budget_allocation (budget_id, target_id, allocated, spent)
select
    '00000000-0000-0000-0000-000000030001',
    r.id,
    250000,
    0
from sales_route r
where r.salesperson_id = '00000000-0000-0000-0000-000000000081'
  and r.is_active
on conflict (budget_id, target_id) do nothing;

update discount_sequence
set budget_id = '00000000-0000-0000-0000-000000030001'
where id = '00000000-0000-0000-0000-000000011004';
