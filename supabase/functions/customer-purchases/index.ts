// =============================================================================
// GET /customer-purchases?customerId=…&months=3
//
// Which products this outlet actually buys, so the stock-count sheet can open on
// them rather than on the whole catalogue.
//
// The app this replaces builds its count sheet the same way — API_GetStockOutlet
// derives the list from the outlet's own orders over three months. Here that
// lives behind customer_purchased_products, and this endpoint is a thin pass so
// the client does not have to know the window or the arithmetic.
//
// Distinct from /customer-orders, which returns whole orders for the history tab.
// This one answers "what does this shop stock", is aggregated per product, and is
// small enough to fetch beside a screen's other calls.
// =============================================================================

import { handler, HttpError, json, unwrap } from "../_shared/client.ts";

const DEFAULT_MONTHS = 3;
const MAX_MONTHS = 24;

interface Row {
  product_id: string;
  base_qty: number;
  order_count: number;
  avg_month_base_qty: number;
  last_order_date: string | null;
}

Deno.serve(handler(async (req, db) => {
  if (req.method !== "GET") throw new HttpError(405, "GET only");

  const params = new URL(req.url).searchParams;
  const customerId = params.get("customerId");
  if (!customerId) throw new HttpError(400, "customerId is required");

  // Clamped rather than trusted: the window reaches the database directly, and
  // an absurd one is a slow query for everybody on the instance.
  const asked = Number(params.get("months") ?? DEFAULT_MONTHS);
  const months = Number.isFinite(asked)
    ? Math.min(Math.max(Math.trunc(asked), 1), MAX_MONTHS)
    : DEFAULT_MONTHS;

  const rows = unwrap(
    await db.rpc("customer_purchased_products", {
      p_customer_id: customerId,
      p_months: months,
    }),
  ) as Row[];

  return json({
    customer_id: customerId,
    months,
    // An outlet the reading rep has never sold to comes back empty, and that is
    // a real answer rather than an error — RLS scopes the orders to them, the
    // same way the legacy proc scopes by SlsperID. The client falls back to the
    // full catalogue instead of showing an empty count sheet.
    products: rows.map((r) => ({
      product_id: r.product_id,
      base_qty: r.base_qty,
      order_count: r.order_count,
      avg_month_base_qty: r.avg_month_base_qty,
      last_order_date: r.last_order_date,
    })),
  });
}));
