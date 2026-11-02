-- =============================================================================
-- Demo dataset: an invented distributor network at a believable scale.
--
-- Shaped on the legacy system's proportions (data.sql), not on its content:
-- every rep visits each outlet on one fixed weekday, mostly every week (F4),
-- some every other week; almost all outlets are general trade; an order has
-- two or three lines on average and sits around a few hundred thousand đồng.
-- Names, phones, addresses and coordinates are invented: real provinces and
-- districts (public geography), made-up shops on common street names.
--
-- What it adds, around the hand-made test accounts (nvbh01-04, gsbh01-03,
-- amkv01, rmv01), which are left as they are with their history:
--   1 more region, 3 more areas, 4 more distributors with a warehouse each
--   1 RM, 3 AMs, 4 SSs and 27 reps (no logins: they exist to be managed)
--   33 more products in 3 more groups, with prices and stock
--   55 outlets and a route per new rep (about 1,500 outlets in all)
--   August to 1 November 2026 for the new reps: clock-ins, visits, orders
--   monthly targets from what each rep sold
--
-- Deterministic: setseed() fixes every random draw, so a fresh database
-- seeded from these migrations gets the same names, routes and figures.
-- =============================================================================

create function pg_temp.pick(a text[]) returns text language sql volatile as $$
    select a[1 + floor(random() * array_length(a, 1))::int]
$$;

create function pg_temp.between(lo int, hi int) returns int language sql volatile as $$
    select lo + floor(random() * (hi - lo + 1))::int
$$;

create function pg_temp.person() returns text language sql volatile as $$
    select pg_temp.pick(array['Nguyễn','Trần','Lê','Phạm','Hoàng','Huỳnh','Phan','Vũ','Võ','Đặng','Bùi','Đỗ','Hồ','Ngô','Dương','Lý'])
        || ' ' || pg_temp.pick(array['Văn','Thị','Hữu','Minh','Ngọc','Thanh','Quốc','Thu','Đức','Hoài'])
        || ' ' || pg_temp.pick(array['An','Bình','Cường','Dũng','Hà','Hải','Hạnh','Hiếu','Hòa','Hùng','Hương','Khang',
                                     'Lan','Linh','Long','Mai','Nam','Ngân','Nhung','Phong','Phúc','Quân','Quỳnh',
                                     'Sơn','Tâm','Thảo','Trang','Trung','Tuấn','Vy','Yến','Khoa','Duyên','Lộc'])
$$;

create function pg_temp.phone() returns text language sql volatile as $$
    select pg_temp.pick(array['090','091','093','094','096','097','098','086','088','089'])
        || lpad(pg_temp.between(0, 9999999)::text, 7, '0')
$$;

do $$
declare
    v_md      uuid := (select id from region where code = 'MD');
    v_mt      uuid;
    r         record;
    v_rep     record;
    v_id      uuid;
    v_seq     int;
    v_n       int;
    v_day     date;
    v_cust    record;
    v_visit   uuid;
    v_order   uuid;
    v_clock   timestamptz;
    v_out     timestamptz;
    v_lines   int;
    v_line    int;
    v_prod    record;
    v_case    boolean;
    v_qty     int;
    v_conv    int;
    v_price   bigint;
    v_gross   bigint;
    v_vat     bigint;
    v_sub     bigint;
    v_vat_sum bigint;
    v_status  order_status;
    v_ono     int;
    v_shop    text;
    v_class   text;
    v_channel text;
    v_wards   int;
    v_ward    record;
    -- The products an order can draw from, by code, with their sales weights.
    v_pid     uuid[];
    v_pvat    int[];
    v_ppcs    bigint[];
    v_pcase   bigint[];
    v_psize   int[];
    v_pw      int[];
    v_wsum    int;
    v_picked  int[];
    v_k       int;
    v_roll    float8;
    v_tries   int;
begin
    perform setseed(0.2026);

    -- -------------------------------------------------------------------------
    -- Places
    -- -------------------------------------------------------------------------
    update region set name = 'Miền Đông' where id = v_md;
    insert into region (code, name) values ('MT', 'Miền Tây') returning id into v_mt;

    insert into area (code, name, region_id) values
        ('DNB2', 'Đông Nam Bộ 2', v_md),
        ('TNB1', 'Tây Nam Bộ 1', v_mt),
        ('TNB2', 'Tây Nam Bộ 2', v_mt);

    update province set name = 'Bình Dương' where code = 'VN-57';
    update district set name = 'Thủ Dầu Một' where code = 'BD-TDM';
    update district set name = 'Thuận An' where code = 'BD-TA';
    update ward set name = v.name from (values ('BD-TDM-PH', 'Phú Hòa'), ('BD-TDM-CN', 'Chánh Nghĩa'), ('BD-TA-LT', 'Lái Thiêu')) v(code, name)
    where ward.code = v.code;

    insert into province (code, name) values
        ('VN-39', 'Đồng Nai'), ('VN-43', 'Bà Rịa - Vũng Tàu'), ('VN-37', 'Tây Ninh'),
        ('VN-CT', 'Cần Thơ'), ('VN-41', 'Long An');

    insert into district (province_id, code, name)
    select p.id, v.code, v.name
    from (values
        ('VN-57', 'BD-DA',  'Dĩ An'),
        ('VN-39', 'DN-BH',  'Biên Hòa'),     ('VN-39', 'DN-TB',  'Trảng Bom'),
        ('VN-43', 'BV-BR',  'Bà Rịa'),       ('VN-43', 'BV-VT',  'Vũng Tàu'),
        ('VN-37', 'TN-TP',  'Tây Ninh'),     ('VN-37', 'TN-HT',  'Hòa Thành'),
        ('VN-CT', 'CT-NK',  'Ninh Kiều'),    ('VN-CT', 'CT-CR',  'Cái Răng'),
        ('VN-41', 'LA-TA',  'Tân An'),       ('VN-41', 'LA-BL',  'Bến Lức')
    ) as v(prov, code, name)
    join province p on p.code = v.prov;

    -- Four wards a district, numbered as most town districts number theirs.
    insert into ward (district_id, code, name)
    select d.id, d.code || '-P' || n, 'Phường ' || n
    from district d cross join generate_series(1, 4) n
    where not exists (select 1 from ward w where w.district_id = d.id);

    -- -------------------------------------------------------------------------
    -- Distributors and warehouses
    -- -------------------------------------------------------------------------
    insert into branch (code, name, address, lat, lng, area_id)
    select v.code, v.name, v.address, v.lat, v.lng, a.id
    from (values
        ('BR03', 'NPP Bà Rịa',   '18 Đường 27 Tháng 4, Bà Rịa',        10.4963, 107.1684, 'DNB2'),
        ('BR04', 'NPP Tây Ninh', '205 Đường 30 Tháng 4, Tây Ninh',     11.3101, 106.0982, 'DNB2'),
        ('BR05', 'NPP Cần Thơ',  '92 Đường 3 Tháng 2, Ninh Kiều',      10.0341, 105.7724, 'TNB1'),
        ('BR06', 'NPP Long An',  '41 Đường Hùng Vương, Tân An',        10.5356, 106.4131, 'TNB2')
    ) as v(code, name, address, lat, lng, area)
    join area a on a.code = v.area;

    insert into site (branch_id, code, name, address)
    select b.id, 'KHO' || substr(b.code, 3), 'Kho ' || substr(b.name, 5), b.address
    from branch b where b.code in ('BR03', 'BR04', 'BR05', 'BR06');

    -- -------------------------------------------------------------------------
    -- Products: three more groups and 33 more items under the house brand
    -- -------------------------------------------------------------------------
    insert into product_category (code, name, sort_order) values
        ('MI', 'Mì & thực phẩm khô', 4),
        ('GV', 'Gia vị', 5),
        ('SU', 'Sữa & đồ uống dinh dưỡng', 6);

    create temp table new_product (code text, name text, cat text, price bigint, case_size int, vat int) on commit drop;
    insert into new_product values
        ('NGK006', 'Nước ngọt NamAn Chanh 330ml',           'NGK',  6000, 24, 1000),
        ('NGK007', 'Trà sữa NamAn chai 330ml',              'NGK',  9000, 24, 1000),
        ('NGK008', 'Nước suối NamAn 1.5L',                  'NGK',  9000, 12,  800),
        ('NGK009', 'Trà bí đao NamAn 310ml',                'NGK',  7000, 24, 1000),
        ('NGK010', 'Nước ép cam NamAn 350ml',               'NGK', 10000, 24, 1000),
        ('BK005',  'Bánh xốp NamAn vị dừa 150g',            'BK',  18000, 24,  800),
        ('BK006',  'Kẹo dẻo NamAn trái cây 80g',            'BK',  12000, 30,  800),
        ('BK007',  'Bánh gạo NamAn 150g',                   'BK',  22000, 20,  800),
        ('BK008',  'Snack khoai tây NamAn 55g',             'BK',  10000, 40,  800),
        ('BK009',  'Bánh bông lan NamAn hộp 168g',          'BK',  35000, 16,  800),
        ('GD004',  'Nước lau sàn NamAn 1L',                 'GD',  32000, 12, 1000),
        ('GD005',  'Bột giặt NamAn 800g',                   'GD',  38000, 12, 1000),
        ('GD006',  'Dầu gội NamAn 340ml',                   'GD',  65000, 12, 1000),
        ('GD007',  'Sữa tắm NamAn 500ml',                   'GD',  79000, 12, 1000),
        ('GD008',  'Nước xả vải NamAn 1.6L',                'GD',  72000,  8, 1000),
        ('MI001',  'Mì ly NamAn vị bò 65g',                 'MI',   9000, 24,  800),
        ('MI002',  'Mì gói NamAn tôm chua cay 75g',         'MI',   4500, 30,  800),
        ('MI003',  'Phở ăn liền NamAn vị gà 65g',           'MI',   8000, 30,  800),
        ('MI004',  'Bún ăn liền NamAn riêu cua 70g',        'MI',   8500, 30,  800),
        ('MI005',  'Miến ăn liền NamAn sườn heo 58g',       'MI',   9500, 24,  800),
        ('MI006',  'Cháo ăn liền NamAn thịt bằm 50g',       'MI',   6000, 50,  800),
        ('GV001',  'Nước mắm NamAn 500ml',                  'GV',  28000, 12,  800),
        ('GV002',  'Nước tương NamAn 500ml',                'GV',  18000, 12,  800),
        ('GV003',  'Hạt nêm NamAn 400g',                    'GV',  32000, 20,  800),
        ('GV004',  'Bột ngọt NamAn 454g',                   'GV',  35000, 20,  800),
        ('GV005',  'Dầu ăn NamAn 1L',                       'GV',  52000, 12,  800),
        ('GV006',  'Tương ớt NamAn 250g',                   'GV',  12000, 24,  800),
        ('SU001',  'Sữa tươi NamAn có đường 180ml',         'SU',   7500, 48,  800),
        ('SU002',  'Sữa tươi NamAn ít đường 180ml',         'SU',   7500, 48,  800),
        ('SU003',  'Sữa chua uống NamAn 110ml',             'SU',   5500, 48,  800),
        ('SU004',  'Sữa đậu nành NamAn 200ml',              'SU',   6500, 36,  800),
        ('SU005',  'Thức uống lúa mạch NamAn 180ml',        'SU',   8000, 48,  800),
        ('SU006',  'Sữa đặc NamAn lon 380g',                'SU',  25000, 48,  800);

    insert into product (code, name, category_id, base_uom, vat_basis_points)
    select n.code, n.name, c.id, 'PCS', n.vat
    from new_product n join product_category c on c.code = n.cat;

    insert into product_uom (product_id, uom_code, conversion_rate, is_default_sale, sort_order)
    select p.id, 'PCS', 1, false, 1 from product p join new_product n on n.code = p.code
    union all
    select p.id, 'CASE', n.case_size, true, 2 from product p join new_product n on n.code = p.code;

    -- A case a little under its pieces, as distributors price it.
    insert into price_list (product_id, uom_code, price, from_date, to_date)
    select p.id, 'PCS', n.price, date '2026-01-01', date '2099-12-31' from product p join new_product n on n.code = p.code
    union all
    select p.id, 'CASE', round(n.price * n.case_size * 0.96 / 1000) * 1000, date '2026-01-01', date '2099-12-31'
    from product p join new_product n on n.code = p.code;

    -- Stock: every product in the new warehouses, the new products in the old.
    insert into site_stock (site_id, product_id, qty_base)
    select s.id, p.id, pg_temp.between(5, 60) * coalesce((select max(conversion_rate) from product_uom u where u.product_id = p.id), 1)
    from site s cross join product p
    where not exists (select 1 from site_stock x where x.site_id = s.id and x.product_id = p.id)
    -- Ordered, so each random draw lands on the same row on every run.
    order by s.code, p.code;

    -- -------------------------------------------------------------------------
    -- People: the new region's RM, an AM for each new area, an SS for each new
    -- distributor, and reps under every SS
    -- -------------------------------------------------------------------------
    insert into salesperson (code, full_name, branch_id, phone, position, supervisor_id)
    values ('rmv02', pg_temp.person(), (select id from branch where code = 'BR05'), pg_temp.phone(), 'rm', null);

    insert into salesperson (code, full_name, branch_id, phone, position, supervisor_id)
    select v.code, pg_temp.person(), b.id, pg_temp.phone(), 'am', s.id
    from (values ('amkv02', 'BR03', 'rmv01'), ('amkv03', 'BR05', 'rmv02'), ('amkv04', 'BR06', 'rmv02')) v(code, br, boss)
    join branch b on b.code = v.br join salesperson s on s.code = v.boss
    order by v.code;

    insert into salesperson (code, full_name, branch_id, phone, position, supervisor_id)
    select v.code, pg_temp.person(), b.id, pg_temp.phone(), 'ss', s.id
    from (values ('gsbh04', 'BR03', 'amkv02'), ('gsbh05', 'BR04', 'amkv02'),
                 ('gsbh06', 'BR05', 'amkv03'), ('gsbh07', 'BR06', 'amkv04')) v(code, br, boss)
    join branch b on b.code = v.br join salesperson s on s.code = v.boss
    order by v.code;

    -- nvbh05..nvbh31, three or four under each SS, at the SS's distributor.
    for r in
        select row_number() over (order by v.ord, g) + 4 as n, s.id as sup, s.branch_id
        from (values ('gsbh01', 1, 3), ('gsbh02', 2, 4), ('gsbh03', 3, 4), ('gsbh04', 4, 4),
                     ('gsbh05', 5, 4), ('gsbh06', 6, 4), ('gsbh07', 7, 4)) v(ss, ord, reps)
        join salesperson s on s.code = v.ss
        cross join lateral generate_series(1, v.reps) g
        order by v.ord, g
    loop
        insert into salesperson (code, full_name, branch_id, phone, position, supervisor_id)
        values ('nvbh' || lpad(r.n::text, 2, '0'), pg_temp.person(), r.branch_id, pg_temp.phone(), 'sr', r.sup);
    end loop;

    -- -------------------------------------------------------------------------
    -- Outlets and routes: 55 a new rep, each on one weekday (Mon-Sat), every
    -- week, or one in twelve every other week
    -- -------------------------------------------------------------------------
    create temp table branch_district (branch_code text, district_code text) on commit drop;
    insert into branch_district values
        ('BR01', 'BD-TDM'), ('BR01', 'BD-TA'), ('BR01', 'BD-DA'),
        ('BR02', 'DN-BH'), ('BR02', 'DN-TB'), ('BR03', 'BV-BR'), ('BR03', 'BV-VT'),
        ('BR04', 'TN-TP'), ('BR04', 'TN-HT'), ('BR05', 'CT-NK'), ('BR05', 'CT-CR'),
        ('BR06', 'LA-TA'), ('BR06', 'LA-BL');

    v_seq := 10000;
    for v_rep in
        select s.id, s.code, s.full_name, s.branch_id, b.code as branch_code, b.lat, b.lng
        from salesperson s join branch b on b.id = s.branch_id
        where s.position = 'sr' and s.code ~ '^nvbh(0[5-9]|[1-9][0-9])$'
        order by s.code
    loop
        insert into sales_route (code, name, branch_id, salesperson_id)
        values ('RT' || substr(v_rep.code, 5), 'Tuyến ' || v_rep.full_name, v_rep.branch_id, v_rep.id)
        returning id into v_id;

        select count(*) into v_wards
        from ward w
        join district d on d.id = w.district_id
        join branch_district bd on bd.district_code = d.code and bd.branch_code = v_rep.branch_code;

        for v_n in 1 .. 55 loop
            v_seq := v_seq + 1;
            -- A ward of the rep's districts, by position in a fixed order.
            select w.id, w.name, d.name as district into v_ward
            from ward w
            join district d on d.id = w.district_id
            join branch_district bd on bd.district_code = d.code and bd.branch_code = v_rep.branch_code
            order by w.code
            offset floor(random() * v_wards)::int limit 1;
            -- Drawn once each, before the insert: a random() inside a lookup's
            -- WHERE is drawn again for every row it tests.
            v_shop := case when random() < 0.72 then 'TH' when random() < 0.55 then 'SM' else 'QC' end;
            v_class := case when random() < 0.15 then 'A' when random() < 0.45 then 'B' else 'C' end;
            v_channel := case when v_shop = 'SM' and random() < 0.4 then 'MT' else 'GT' end;
            insert into customer (code, name, branch_id, phone, address, ward_id, lat, lng,
                                  class_id, channel_id, shop_type_id, approval_status, contact_name, credit_limit)
            select 'KH' || v_seq,
                   case st.code when 'TH' then 'Tạp hóa ' when 'SM' then 'Siêu thị mini ' else 'Quán cà phê ' end
                       || pg_temp.pick(array['Minh Phát','Hồng Phúc','Thanh Tâm','Kim Ngân','Phước Lộc','An Bình',
                          'Thành Đạt','Ngọc Lan','Bảo Châu','Tấn Phát','Hoàng Gia','Phú Quý','Thu Hà','Mỹ Linh',
                          'Đức Thịnh','Hải Yến','Tuấn Kiệt','Bích Ngọc','Hưng Thịnh','Vạn Lợi','Song Toàn',
                          'Quỳnh Như','Gia Huy','Thanh Bình','Kim Phượng','Tân Tiến','Hòa Bình','Phúc Lộc',
                          'Ánh Dương','Út Mai','Năm Lộc','Cô Ba','Chị Tư','Anh Sáu','Bảy Liên','Tám Hùng',
                          'Hai Thanh','Ba Nhỏ','Thiên Ân','Lộc Phát','Như Ý','Đại Phát','Kim Thoa','Bé Na']),
                   v_rep.branch_id,
                   pg_temp.phone(),
                   pg_temp.between(1, 450) || ' ' || pg_temp.pick(array['Lê Lợi','Trần Hưng Đạo','Nguyễn Trãi',
                          'Hai Bà Trưng','Lý Thường Kiệt','Nguyễn Huệ','Phan Chu Trinh','Hùng Vương',
                          'Cách Mạng Tháng 8','Trần Phú','Quang Trung','Nguyễn Văn Cừ','Lê Duẩn','Điện Biên Phủ',
                          'Ngô Quyền','Bạch Đằng','Phạm Văn Đồng','30 Tháng 4','Nguyễn Thị Minh Khai','Lê Hồng Phong'])
                       || ', ' || v_ward.name || ', ' || v_ward.district,
                   v_ward.id,
                   v_rep.lat + (random() - 0.5) * 0.09,
                   v_rep.lng + (random() - 0.5) * 0.09,
                   (select id from customer_class where code = v_class),
                   (select id from sales_channel where code = v_channel),
                   st.id,
                   'approved',
                   pg_temp.person(),
                   pg_temp.between(2, 20) * 1000000
            from (select * from shop_type where code = v_shop) st
            returning id into v_cust;

            insert into route_customer (sales_route_id, customer_id, visit_order, visit_weekdays, visit_weeks)
            values (v_id, v_cust.id, (v_n - 1) / 6 + 1, array[((v_n - 1) % 6 + 1)::smallint],
                    case when v_n % 12 = 0 then array[1, 3]::smallint[]
                         when v_n % 12 = 6 then array[2, 4]::smallint[]
                         else array[1, 2, 3, 4]::smallint[] end);
        end loop;
    end loop;

    -- -------------------------------------------------------------------------
    -- History, 1 August to 1 November 2026, new reps only: a clock-in, the
    -- day's route stops (one in fourteen skipped), an order at a little over
    -- half of them, a clock-out
    -- -------------------------------------------------------------------------
    -- The sellable products in code order, as arrays the order lines draw from
    -- by weight: drinks and noodles sell most, household goods least.
    select array_agg(p.id order by p.code),
           array_agg(p.vat_basis_points order by p.code),
           array_agg(pc.price order by p.code),
           array_agg(pc2.price order by p.code),
           array_agg(u.conversion_rate order by p.code),
           array_agg(case cat.code when 'NGK' then 5 when 'MI' then 5 when 'SU' then 3
                                   when 'BK' then 3 when 'GV' then 2 else 1 end order by p.code)
      into v_pid, v_pvat, v_ppcs, v_pcase, v_psize, v_pw
    from product p
    join product_category cat on cat.id = p.category_id
    join price_list pc on pc.product_id = p.id and pc.uom_code = 'PCS' and pc.class_id is null
    join price_list pc2 on pc2.product_id = p.id and pc2.uom_code = 'CASE' and pc2.class_id is null
    join product_uom u on u.product_id = p.id and u.uom_code = 'CASE';
    select sum(w) into v_wsum from unnest(v_pw) w;

    for v_rep in
        select s.id, s.code, s.branch_id, b.lat, b.lng
        from salesperson s join branch b on b.id = s.branch_id
        where s.position = 'sr' and s.code ~ '^nvbh(0[5-9]|[1-9][0-9])$'
        order by s.code
    loop
        v_ono := 0;
        for v_day in select d::date from generate_series(date '2026-08-01', date '2026-11-01', interval '1 day') d
                     where extract(isodow from d) < 7
        loop
            -- One day in twenty-five off, unannounced.
            continue when random() < 0.04;

            v_clock := (v_day + make_time(7, pg_temp.between(35, 59), 0)) at time zone 'Asia/Ho_Chi_Minh';
            insert into timekeeping (salesperson_id, branch_id, type, happened_at, lat, lng, accuracy_m, distance_m, work_date)
            values (v_rep.id, v_rep.branch_id, 'check_in', v_clock, v_rep.lat, v_rep.lng, pg_temp.between(5, 25), pg_temp.between(3, 60), v_day);

            v_clock := v_clock + make_interval(mins => pg_temp.between(30, 50));
            for v_cust in
                select rc.customer_id, c.lat, c.lng
                from route_customer rc
                join sales_route sr on sr.id = rc.sales_route_id and sr.salesperson_id = v_rep.id
                join customer c on c.id = rc.customer_id
                where on_route_day(rc.visit_weekdays, rc.visit_weeks, v_day)
                order by rc.visit_order
            loop
                continue when random() < 0.07;

                v_out := v_clock + make_interval(mins => pg_temp.between(6, 22));
                v_status := null;
                insert into visit (customer_id, salesperson_id, branch_id, visit_date, status,
                                   check_in_at, check_in_lat, check_in_lng, check_in_accuracy_m, check_in_distance_m,
                                   check_out_at, check_out_lat, check_out_lng)
                values (v_cust.customer_id, v_rep.id, v_rep.branch_id, v_day,
                        (case when random() < 0.04 then 'closed' else 'completed' end)::visit_status,
                        v_clock, v_cust.lat + (random() - 0.5) * 0.0004, v_cust.lng + (random() - 0.5) * 0.0004,
                        pg_temp.between(5, 30), pg_temp.between(2, 45),
                        v_out, v_cust.lat, v_cust.lng)
                returning id into v_visit;

                if (select status from visit where id = v_visit) = 'completed' then
                    if random() < 0.58 then
                        v_ono := v_ono + 1;
                        v_status := case
                            when v_day >= date '2026-10-30' then 'new'
                            when random() < 0.02 then 'cancelled'
                            when random() < 0.04 then 'new'
                            else 'confirmed' end;
                        insert into sales_order (id, order_no, customer_id, salesperson_id, branch_id, visit_id, order_date,
                                                 status, client_created_at, created_at)
                        values (gen_random_uuid(), v_rep.code || '-' || to_char(v_day, 'YYYYMMDD') || '-G' || lpad(v_ono::text, 4, '0'),
                                v_cust.customer_id, v_rep.id, v_rep.branch_id, v_visit, v_day, v_status, v_out, v_out)
                        returning id into v_order;

                        -- One to six lines, most often two or three.
                        v_lines := (array[1,1,2,2,2,3,3,3,4,4,5,6])[pg_temp.between(1, 12)];
                        v_sub := 0; v_vat_sum := 0; v_line := 0; v_picked := '{}';
                        while v_line < v_lines loop
                            -- A product by weight, drawn again if the order has it.
                            v_tries := 0;
                            loop
                                v_roll := random() * v_wsum;
                                v_k := 1;
                                while v_roll >= v_pw[v_k] loop
                                    v_roll := v_roll - v_pw[v_k];
                                    v_k := v_k + 1;
                                end loop;
                                v_tries := v_tries + 1;
                                exit when not (v_k = any (v_picked)) or v_tries > 20;
                            end loop;
                            exit when v_k = any (v_picked);
                            v_picked := v_picked || v_k;

                            v_line := v_line + 1;
                            v_case := random() < 0.35;
                            v_qty := case when v_case then (array[1,1,1,2,2,3,5])[pg_temp.between(1, 7)]
                                          else (array[2,3,5,6,10,12,20,24,30])[pg_temp.between(1, 9)] end;
                            v_conv := case when v_case then v_psize[v_k] else 1 end;
                            v_price := case when v_case then v_pcase[v_k] else v_ppcs[v_k] end;
                            v_gross := v_qty * v_price;
                            v_vat := round(v_gross * v_pvat[v_k] / 10000.0);
                            insert into sales_order_line (order_id, line_no, product_id, uom_code, qty, conversion_rate, base_qty,
                                                          price, vat_basis_points, gross_amount, discount_amount, vat_amount, line_amount, is_free)
                            values (v_order, v_line, v_pid[v_k], case when v_case then 'CASE' else 'PCS' end, v_qty, v_conv,
                                    v_qty * v_conv, v_price, v_pvat[v_k], v_gross, 0, v_vat, v_gross + v_vat, false);
                            v_sub := v_sub + v_gross; v_vat_sum := v_vat_sum + v_vat;
                        end loop;

                        update sales_order
                        set sub_total = v_sub, discount_amount = 0, vat_amount = v_vat_sum,
                            total_amount = v_sub + v_vat_sum, client_total_amount = v_sub + v_vat_sum
                        where id = v_order;
                    else
                        update visit set status = 'no_order' where id = v_visit;
                    end if;
                end if;

                v_clock := v_out + make_interval(mins => pg_temp.between(8, 25));
            end loop;

            insert into timekeeping (salesperson_id, branch_id, type, happened_at, lat, lng, accuracy_m, distance_m, work_date)
            values (v_rep.id, v_rep.branch_id, 'check_out', greatest(v_clock, (v_day + time '16:45') at time zone 'Asia/Ho_Chi_Minh'),
                    v_rep.lat, v_rep.lng, pg_temp.between(5, 25), pg_temp.between(3, 60), v_day);
        end loop;
    end loop;

    -- -------------------------------------------------------------------------
    -- Targets: each month a little above or below what the rep sold, so the
    -- overview has reps on both sides of 100%; November from October's pace
    -- -------------------------------------------------------------------------
    -- A loop rather than one INSERT ... SELECT: the draws then follow the rep
    -- codes, not the order a hash aggregate happens to emit its groups in.
    for r in
        select o.salesperson_id, m.month, sum(o.total_amount) as revenue, count(*) as orders
        from (values (date '2026-08-01'), (date '2026-09-01'), (date '2026-10-01')) m(month)
        join sales_order o on o.order_date between m.month and (m.month + interval '1 month - 1 day')::date
                          and o.status <> 'cancelled'
        join salesperson s on s.id = o.salesperson_id and s.code ~ '^nvbh(0[5-9]|[1-9][0-9])$'
        group by o.salesperson_id, s.code, m.month
        order by s.code, m.month
    loop
        insert into sales_target (salesperson_id, period_month, revenue_target, order_target)
        values (r.salesperson_id, r.month,
                round(r.revenue * (0.85 + random() * 0.4) / 1000000) * 1000000,
                round(r.orders * (0.9 + random() * 0.3)))
        on conflict (salesperson_id, period_month) do nothing;
    end loop;

    insert into sales_target (salesperson_id, period_month, revenue_target, order_target)
    select t.salesperson_id, date '2026-11-01',
           round(t.revenue_target * 1.05 / 1000000) * 1000000, round(t.order_target * 1.05)
    from sales_target t
    join salesperson s on s.id = t.salesperson_id and s.code ~ '^nvbh(0[5-9]|[1-9][0-9])$'
    where t.period_month = date '2026-10-01'
    on conflict (salesperson_id, period_month) do nothing;
end;
$$;
