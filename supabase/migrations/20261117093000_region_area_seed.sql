-- =============================================================================
-- Dữ liệu mẫu vùng / khu vực / NPP thứ hai
--
--   Miền Đông ─ Đông Nam Bộ 1 ─┬─ BR01 NPP Mien Dong (Bình Dương)
--                              └─ BR02 NPP Dong Nai  (Biên Hòa)
--
--   rmv01 (RM) ─ amkv01 (AM) ─┬─ gsbh01, gsbh02 (BR01)
--                             └─ gsbh03 (SS, BR02) ─ nvbh04 (route R04)
--
-- BR02 gets two outlets and a route so its filter has something to show; no
-- orders, prices or targets, which the dashboard shows as zero. Logins come
-- from scripts/seed_auth_users.ps1.
-- =============================================================================

insert into region (id, code, name)
values ('00000000-0000-0000-0000-0000000000e1', 'MD', 'Miền Đông')
on conflict (id) do nothing;

insert into area (id, code, name, region_id)
values ('00000000-0000-0000-0000-0000000000e2', 'DNB1', 'Đông Nam Bộ 1', '00000000-0000-0000-0000-0000000000e1')
on conflict (id) do nothing;

update branch set area_id = '00000000-0000-0000-0000-0000000000e2'
where id = '00000000-0000-0000-0000-000000000010' and area_id is null;

insert into branch (id, code, name, address, lat, lng, area_id)
values ('00000000-0000-0000-0000-000000000011', 'BR02', 'NPP Dong Nai',
        '25 Pham Van Thuan, Bien Hoa, Dong Nai', 10.9574, 106.8427, '00000000-0000-0000-0000-0000000000e2')
on conflict (id) do nothing;

insert into salesperson (id, code, full_name, branch_id, phone, allow_multi_device, position, supervisor_id)
values
    ('00000000-0000-0000-0000-000000000088', 'gsbh03', 'Ngo Van Nai', '00000000-0000-0000-0000-000000000011', '0909000088', true, 'ss',
     '00000000-0000-0000-0000-000000000086'),
    ('00000000-0000-0000-0000-000000000089', 'nvbh04', 'Ly Thi Hoa',  '00000000-0000-0000-0000-000000000011', '0909000089', true, 'sr',
     '00000000-0000-0000-0000-000000000088')
on conflict (id) do nothing;

-- Segmentation copied from an existing outlet, so the new ones read like the rest.
insert into customer (id, code, name, branch_id, phone, address, lat, lng, ward_id, class_id, channel_id, shop_type_id)
select v.id::uuid, v.code, v.name, '00000000-0000-0000-0000-000000000011', v.phone, v.address, v.lat, v.lng,
       k.ward_id, k.class_id, k.channel_id, k.shop_type_id
from (values
        ('00000000-0000-0000-0000-0000000001b1', 'KH101', 'Tap hoa Bien Hoa', '0251000101', '12 Vo Thi Sau, Bien Hoa',    10.9512, 106.8390),
        ('00000000-0000-0000-0000-0000000001b2', 'KH102', 'Quan nuoc Tan Mai', '0251000102', '88 Dong Khoi, Tan Mai, Bien Hoa', 10.9601, 106.8512)
     ) as v(id, code, name, phone, address, lat, lng)
cross join (select ward_id, class_id, channel_id, shop_type_id from customer where code = 'KH004') k
on conflict (id) do nothing;

insert into sales_route (id, code, name, branch_id, salesperson_id, is_active)
values ('00000000-0000-0000-0000-0000000000a4', 'R04', 'Tuyến Lý Thị Hoa',
        '00000000-0000-0000-0000-000000000011', '00000000-0000-0000-0000-000000000089', true)
on conflict (id) do nothing;

insert into route_customer (sales_route_id, customer_id, visit_order, visit_weekdays)
select '00000000-0000-0000-0000-0000000000a4', c.id, v.ord, v.days::smallint[]
from (values ('KH101', 1, '{1,3,5}'), ('KH102', 2, '{2,4,6}')) as v(code, ord, days)
join customer c on c.code = v.code
on conflict (sales_route_id, customer_id) do nothing;
