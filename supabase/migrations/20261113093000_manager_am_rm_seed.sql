-- =============================================================================
-- Dữ liệu mẫu AM / RM
--
--   rmv01 (RM) ─ amkv01 (AM) ─┬─ gsbh01 (SS) ─ nvbh01, nvbh02
--                             └─ gsbh02 (SS) ─ nvbh03   (route R03)
--
-- One branch still: a second distributor would need its own outlets, prices
-- and orders to show anything. Logins come from scripts/seed_auth_users.ps1.
-- =============================================================================

insert into salesperson (id, code, full_name, branch_id, phone, allow_multi_device, position)
values
    ('00000000-0000-0000-0000-000000000087', 'rmv01',  'Pham Van Vung',  '00000000-0000-0000-0000-000000000010', '0909000087', true, 'rm'),
    ('00000000-0000-0000-0000-000000000086', 'amkv01', 'Vo Thi Khu',     '00000000-0000-0000-0000-000000000010', '0909000086', true, 'am'),
    ('00000000-0000-0000-0000-000000000085', 'gsbh02', 'Dang Van Sat',   '00000000-0000-0000-0000-000000000010', '0909000085', true, 'ss'),
    ('00000000-0000-0000-0000-000000000084', 'nvbh03', 'Bui Thi Ban',    '00000000-0000-0000-0000-000000000010', '0909000084', true, 'sr')
on conflict (id) do nothing;

update salesperson set supervisor_id = '00000000-0000-0000-0000-000000000087' where code = 'amkv01' and supervisor_id is null;
update salesperson set supervisor_id = '00000000-0000-0000-0000-000000000086' where code in ('gsbh01', 'gsbh02') and supervisor_id is null;
update salesperson set supervisor_id = '00000000-0000-0000-0000-000000000085' where code = 'nvbh03' and supervisor_id is null;

insert into sales_route (id, code, name, branch_id, salesperson_id, is_active)
values ('00000000-0000-0000-0000-0000000000a3', 'R03', 'Tuyến Bùi Thị Bán',
        '00000000-0000-0000-0000-000000000010', '00000000-0000-0000-0000-000000000084', true)
on conflict (id) do nothing;

insert into route_customer (sales_route_id, customer_id, visit_order, visit_weekdays)
select '00000000-0000-0000-0000-0000000000a3', c.id, v.ord, v.days::smallint[]
from (values ('KH004', 1, '{1,3,5}'), ('KH005', 2, '{2,4,6}'), ('KH008', 3, '{1,4}')) as v(code, ord, days)
join customer c on c.code = v.code
on conflict (sales_route_id, customer_id) do nothing;
