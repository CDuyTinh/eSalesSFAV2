-- =============================================================================
-- Ship-to addresses for the demo outlets
--
-- Two shapes, because they are the two the feature exists for: a shop whose
-- goods go to a warehouse round the back, and a shop that receives them itself
-- but bills a different company. A third outlet is left with none at all, so
-- the "outlet's own address" path is reachable without editing data.
-- =============================================================================

insert into customer_ship_address
    (customer_id, code, recipient, phone, address, tax_code, is_default)
select c.id, v.code, v.recipient, v.phone, v.address, v.tax_code, v.is_default
from (values
    -- KH001 delivers to its own front door, or to the warehouse behind it.
    ('KH001', 'ST-01', 'Tap hoa Minh Anh',        '0281234001', '45 Nguyen Trai, Phu Hoa',        null,          true),
    ('KH001', 'ST-02', 'Kho Minh Anh - Bau Bang', '0281234011', 'Lo B2 KCN Bau Bang, Bau Bang',   null,          false),
    -- KH003 receives at the shop but invoices through its parent company.
    ('KH003', 'ST-01', 'Sieu thi mini Thanh Cong', '0281234003', '8 Cach Mang Thang 8, Chanh Nghia', '3700123456', true),
    ('KH003', 'ST-02', 'Cty TNHH Thanh Cong',      '0281234013', '120 Yersin, Phu Cuong',            '3700123456', false)
) as v(customer_code, code, recipient, phone, address, tax_code, is_default)
join customer c on c.code = v.customer_code
on conflict (customer_id, code) do nothing;
