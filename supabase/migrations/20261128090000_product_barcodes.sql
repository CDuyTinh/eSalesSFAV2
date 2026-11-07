-- =============================================================================
-- Product barcodes, for scanning at the counter
--
-- product.barcode has existed since the orders schema and was never filled.
-- The app now scans it: in Đặt hàng a scan adds one of the product to the
-- basket. Demo data gets EAN-13 codes under Vietnam's GS1 prefix (893), built
-- from the product code so they are stable across re-seeds, with a correct
-- check digit so any scanner reads them. A real distributor's import replaces
-- them with the codes printed on the goods.
--
-- Only products with no barcode are touched, and only in the demo branch's
-- code ranges, so a real code already entered is never overwritten.
-- =============================================================================

create or replace function ean13_check_digit(p_first12 text)
returns text
language sql
immutable
as $$
    -- Weights 1,3,1,3... over the first twelve digits; the check digit brings
    -- the weighted sum up to a multiple of ten.
    select ((10 - (sum(substr(p_first12, i, 1)::int * case when i % 2 = 0 then 3 else 1 end) % 10)) % 10)::text
    from generate_series(1, 12) as i
$$;

with numbered as (
    select id, code,
           -- 893 + 4-digit family + 5-digit item: BK001 -> 893 0201 00001.
           '893' ||
           lpad((case regexp_replace(code, '[0-9]+$', '')
                     when 'NGK' then 101 when 'BK' then 201 when 'MI' then 301
                     when 'SU' then 401 when 'GV' then 501 when 'GD' then 601
                     else 901 end)::text, 4, '0') ||
           lpad(regexp_replace(code, '^[A-Z]+', ''), 5, '0') as first12
    from product
    where (barcode is null or barcode = '')
      and code ~ '^(NGK|BK|MI|SU|GV|GD)[0-9]{1,5}$'
)
update product p
   set barcode = n.first12 || ean13_check_digit(n.first12)
  from numbered n
 where p.id = n.id;

comment on column product.barcode is
    'EAN-13 printed on the base unit. Scanned in Đặt hàng to add one to the basket.';
