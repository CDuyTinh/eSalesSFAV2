-- =============================================================================
-- Demo data: no real brands.
--
-- The seed borrowed real product and company names (soft drinks, snacks,
-- detergents, the competitors' too). This is a demo dataset for an app of its
-- own, so they are replaced with an invented house brand, "NamAn", and
-- invented competitors. Ids are kept: every order line, programme, target and
-- photo that points at these rows still points at the same thing, now named
-- differently.
--
-- Category names gain their diacritics while here.
-- =============================================================================

update product_category set name = v.name
from (values
    ('NGK', 'Nước giải khát'),
    ('BK',  'Bánh kẹo'),
    ('GD',  'Hóa phẩm gia dụng')
) as v(code, name)
where product_category.code = v.code;

update product set name = v.name, updated_at = now()
from (values
    ('NGK001', 'Nước ngọt NamAn Cola 330ml'),
    ('NGK002', 'Nước ngọt NamAn Cam 330ml'),
    ('NGK003', 'Nước suối NamAn 500ml'),
    ('NGK004', 'Trà xanh NamAn vị chanh 455ml'),
    ('NGK005', 'Nước tăng lực NamAn 330ml'),
    ('BK001',  'Bánh quy kem NamAn gói 119g'),
    ('BK002',  'Bánh quy bơ NamAn hộp 336g'),
    ('BK003',  'Kẹo sữa NamAn gói 105g'),
    ('BK004',  'Snack tôm cay NamAn 40g'),
    ('GD001',  'Nước giặt NamAn 2.7kg'),
    ('GD002',  'Nước rửa chén NamAn hương chanh 750ml'),
    ('GD003',  'Kem đánh răng NamAn 180g')
) as v(code, name)
where product.code = v.code;

-- Competitors: invented companies and products, in the same four lines of
-- business the survey asks about.
update competitor_vendor set code = v.new_code, name = v.name
from (values
    ('THP',    'BMI', 'Công ty Đồ uống Bình Minh'),
    ('URC',    'HAU', 'Công ty Thực phẩm Hải Âu'),
    ('NESTLE', 'PGI', 'Công ty Nước khoáng Phú Gia'),
    ('ORION',  'KLO', 'Công ty Bánh kẹo Kim Long')
) as v(old_code, new_code, name)
where competitor_vendor.code = v.old_code;

update competitor_product set code = v.new_code, name = v.name
from (values
    ('THP-N1',  'BMI-TL',  'Nước tăng lực Bình Minh 330ml'),
    ('URC-C2',  'HAU-TX',  'Trà xanh Hải Âu 455ml'),
    ('NES-LAV', 'PGI-NS',  'Nước suối Phú Gia 500ml'),
    ('ORI-CP',  'KLO-BX',  'Bánh xốp Kim Long hộp 396g')
) as v(old_code, new_code, name)
where competitor_product.code = v.old_code;

-- The one promotion whose wording named two brands, and the order discount
-- line that copied it.
update discount_sequence
set name = 'Mua 5 thùng nước ngọt Cola tặng 1 thùng nước ngọt Cam'
where name = 'Mua 5 thùng Coca tặng 1 thùng Pepsi';

update sales_order_discount
set descr = replace(descr, 'Mua 5 thùng Coca tặng 1 thùng Pepsi', 'Mua 5 thùng nước ngọt Cola tặng 1 thùng nước ngọt Cam')
where descr like 'Mua 5 thùng Coca tặng 1 thùng Pepsi%';
