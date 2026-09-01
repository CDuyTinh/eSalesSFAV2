-- =============================================================================
-- What this outlet has ordered before
--
-- The tab a rep opens when the shop says "how much did I take last time" — the
-- one question on the customer screen that is not answerable from anything the
-- device already holds.
--
-- Legacy FetchOrdHisByCust is paged and date-ranged, with a filter box and a
-- "view all" screen behind it. Neither is here yet: the useful answer is the
-- last handful of orders, and a rep hunting through a year of them is a
-- reporting job rather than something done standing in a doorway. The limit is a
-- parameter so a screen can raise it without a migration.
--
-- Lines come with the orders rather than on tap. An order has a dozen lines at
-- most, and a per-order round trip inside a shop with poor signal is the slower
-- answer even though it moves less data.
-- =============================================================================

create or replace function customer_order_history(
    p_customer_id uuid,
    p_limit       integer default 20
)
returns jsonb
language sql
stable
security invoker
set search_path = public, pg_temp
as $$
    -- security invoker again: sales_order is scoped by RLS to the rep's own
    -- orders. So this is the history *this rep* wrote for the outlet, not the
    -- outlet's whole history — which is the honest thing to show, since a figure
    -- including another rep's orders is one they cannot explain to the shop.
    select coalesce(
        jsonb_agg(
            jsonb_build_object(
                'order_id',     o.id,
                'order_no',     o.order_no,
                'order_date',   o.order_date,
                'status',       o.status,
                'total_amount', o.total_amount,
                'lines',        o.lines
            )
            order by o.order_date desc, o.order_no desc
        ),
        '[]'::jsonb
    )
    from (
        select so.id,
               so.order_no,
               so.order_date,
               so.status,
               so.total_amount,
               coalesce((
                   select jsonb_agg(
                              jsonb_build_object(
                                  'product_code', p.code,
                                  'product_name', p.name,
                                  'uom_code',     l.uom_code,
                                  'qty',          l.qty,
                                  'line_amount',  l.line_amount
                              )
                              order by l.line_no
                          )
                     from sales_order_line l
                     join product p on p.id = l.product_id
                    where l.order_id = so.id
               ), '[]'::jsonb) as lines
          from sales_order so
         where so.customer_id = p_customer_id
         -- Cancelled orders stay out. They are kept for audit, but a rep reading
         -- history back to a shop must not quote one that was never delivered.
           and so.status <> 'cancelled'
         order by so.order_date desc, so.order_no desc
         limit p_limit
    ) o;
$$;

revoke execute on function customer_order_history(uuid, integer) from public;
grant execute on function customer_order_history(uuid, integer) to authenticated;
