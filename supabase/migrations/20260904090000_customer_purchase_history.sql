-- =============================================================================
-- What this outlet actually buys
--
-- The stock-count step lists the whole catalogue, and with a few hundred SKUs
-- that is a step a rep cannot use: they scroll past everything the shop has
-- never stocked to find the dozen lines that matter.
--
-- The app this replaces solves it in API_GetStockOutlet, and not with a
-- must-stock list — it builds the count sheet from the outlet's own order
-- history over the last three months (`OM_SalesOrd` joined to `OM_SalesOrdDet`,
-- status 'C'), plus whatever is already on today's sheet, plus a + button for
-- anything else. This is the first of those three.
--
-- `avg_month_base_qty` is the legacy's own AvgQty: total over the window divided
-- by the window's length in months. Legacy also subtracts return order types
-- (FR/IR); this build has no return types yet, so the subtraction has nothing to
-- take and is left out rather than faked.
--
-- security invoker on purpose. `sales_order` is scoped by RLS to the reading
-- rep's own orders, which is exactly the scoping the legacy proc writes by hand
-- (`o.SlsPerID = @SlsperID`). It carries the same limitation: an outlet covered
-- by a colleague last month contributes nothing, and the caller has to cope with
-- an empty answer rather than treat it as "this shop buys nothing".
-- =============================================================================

create or replace function customer_purchased_products(
    p_customer_id uuid,
    p_months integer default 3
)
returns table (
    product_id         uuid,
    base_qty           bigint,
    order_count        integer,
    avg_month_base_qty integer,
    last_order_date    date
)
language sql
stable
security invoker
set search_path = public, pg_temp
as $$
    select
        l.product_id,
        sum(l.base_qty)::bigint                                as base_qty,
        count(distinct o.id)::integer                          as order_count,
        -- Rounded, not truncated: a shop taking one case every other month
        -- averages half a case, and reporting that as zero is the same as
        -- saying they do not stock it.
        round(sum(l.base_qty)::numeric / greatest(p_months, 1))::integer
                                                               as avg_month_base_qty,
        max(o.order_date)                                      as last_order_date
    from sales_order o
    join sales_order_line l on l.order_id = o.id
    where o.customer_id = p_customer_id
      and o.status <> 'cancelled'
      and o.order_date >= (current_date - make_interval(months => greatest(p_months, 1)))
    group by l.product_id;
$$;

comment on function customer_purchased_products(uuid, integer) is
    'Products this outlet has bought in the last N months, from the reading '
    'rep''s own orders. Backs the stock-count sheet''s default filter.';
