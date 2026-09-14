-- =============================================================================
-- A ship-to address on the outlet the demo route actually calls at
--
-- The first seed gave addresses to KH001 and KH003, neither of which is on the
-- demo rep's route today. Anyone exercising the feature checks in wherever the
-- route sends them, sees no delivery card, and cannot tell a working feature
-- from a missing one. KH006 gets a warehouse so the card is reachable by
-- walking the app rather than by editing data first.
--
-- KH002 still has none, which keeps the "delivered to the outlet itself" path
-- reachable the same way.
-- =============================================================================

insert into customer_ship_address
    (customer_id, code, recipient, phone, address, tax_code, is_default)
select c.id, v.code, v.recipient, v.phone, v.address, v.tax_code, v.is_default
from (values
    ('KH006', 'ST-01', 'Tap hoa Tuoi Tre',        '0281234006', '5 Hung Vuong, Lai Thieu',      null, true),
    ('KH006', 'ST-02', 'Kho Tuoi Tre - An Thanh', '0281234016', '27 Duong DT743, An Thanh',     null, false)
) as v(customer_code, code, recipient, phone, address, tax_code, is_default)
join customer c on c.code = v.customer_code
on conflict (customer_id, code) do nothing;
