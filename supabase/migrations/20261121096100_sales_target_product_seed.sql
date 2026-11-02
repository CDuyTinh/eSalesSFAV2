-- Demo targets by group and by product for nvbh01 and nvbh02, October (which
-- has orders to measure against) and November. Fake data, as the rest of the
-- seed; real figures come from head office.
insert into sales_target_product (salesperson_id, period_month, category_id, product_id, target_amount)
select s.id, m.month, c.id, null, t.amount
from (values
        ('nvbh01', 'NGK', 120000000), ('nvbh01', 'BK', 15000000), ('nvbh01', 'GD', 8000000),
        ('nvbh02', 'NGK',  60000000), ('nvbh02', 'BK', 20000000)
     ) as t(rep, cat, amount)
join salesperson s on s.code = t.rep
join product_category c on c.code = t.cat
cross join (values (date '2026-10-01'), (date '2026-11-01')) as m(month)
on conflict do nothing;

insert into sales_target_product (salesperson_id, period_month, category_id, product_id, target_amount)
select s.id, m.month, null, p.id, t.amount
from (values ('nvbh01', 'NGK001', 45000000), ('nvbh02', 'NGK001', 25000000)) as t(rep, prod, amount)
join salesperson s on s.code = t.rep
join product p on p.code = t.prod
cross join (values (date '2026-10-01'), (date '2026-11-01')) as m(month)
on conflict do nothing;
