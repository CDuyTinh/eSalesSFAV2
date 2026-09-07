-- =============================================================================
-- Dữ liệu mẫu: khảo sát đối thủ
--
-- Enough of a survey to work the screen against: three criteria, four of our
-- products each paired with the rival that sits next to it on the shelf.
--
-- The competitors here are brands the demo catalogue does not itself carry, so
-- nothing in the pairing is the company selling against itself.
-- =============================================================================

insert into competitor_vendor (code, name) values
    ('THP',    'Tan Hiep Phat'),
    ('URC',    'URC Viet Nam'),
    ('NESTLE', 'Nestle Waters'),
    ('ORION',  'Orion Food Vina')
on conflict (code) do nothing;

insert into competitor_product (vendor_id, code, name)
select v.id, x.code, x.name
from (values
    ('THP',    'THP-N1',   'Nuoc tang luc Number 1 330ml'),
    ('URC',    'URC-C2',   'Tra xanh C2 455ml'),
    ('NESTLE', 'NES-LAV',  'Nuoc suoi La Vie 500ml'),
    ('ORION',  'ORI-CP',   'Banh Chocopie hop 396g')
) as x(vendor, code, name)
join competitor_vendor v on v.code = x.vendor
on conflict (vendor_id, code) do nothing;

insert into competitor_criteria (code, name, hint) values
    ('GIABAN', 'Gia ban le',        'Gia mot don vi le tai quay'),
    ('SOMAT',  'So mat trung bay',  'Dem so mat hang quay ra phia khach'),
    ('CTKM',   'Khuyen mai dang chay', 'Ghi ngan gon, khong co thi de trong')
on conflict (code) do nothing;

insert into competitor_survey (code, name, from_date, to_date)
values (
    'KSDT-Q3',
    'Khao sat doi thu quy 3',
    current_date - 30,
    current_date + 60
)
on conflict (code) do nothing;

insert into competitor_survey_criteria (survey_id, criteria_id, is_required, sort_order)
select s.id, c.id, x.required, x.sort_order
from (values
    ('GIABAN', true,  1),
    ('SOMAT',  true,  2),
    -- Optional on purpose, so the completion rule has something to ignore.
    ('CTKM',   false, 3)
) as x(code, required, sort_order)
join competitor_criteria c on c.code = x.code
cross join competitor_survey s
where s.code = 'KSDT-Q3'
on conflict (survey_id, criteria_id) do nothing;

insert into competitor_survey_item (survey_id, product_id, competitor_product_id, sort_order)
select s.id, p.id, cp.id, x.sort_order
from (values
    ('NGK001', 'THP-N1',  1),
    ('NGK003', 'NES-LAV', 2),
    ('NGK004', 'URC-C2',  3),
    ('BK002',  'ORI-CP',  4)
) as x(product, competitor, sort_order)
join product p on p.code = x.product
join competitor_product cp on cp.code = x.competitor
cross join competitor_survey s
where s.code = 'KSDT-Q3'
on conflict (survey_id, product_id, competitor_product_id) do nothing;
