// =============================================================================
// POST /promotions   { customer_id, lines: [{product_id, uom_code, qty}] }
//
// What this basket would earn, if it were submitted now. The legacy's
// api/Promos/FetchPromo, which the order screen calls every time the cart
// changes so the rep can tell the customer "two more cases and you get a free
// one" while they are still deciding.
//
// A preview and nothing more. `submit_order` recomputes all of this from the
// lines it actually books, so a stale or forged answer here cannot move money —
// it can only mislead the rep for as long as it takes them to press send.
// =============================================================================

import { handler, HttpError, json, unwrap } from "../_shared/client.ts";

interface Line {
  product_id?: string;
  uom_code?: string;
  qty?: number;
}

Deno.serve(handler(async (req, db) => {
  if (req.method !== "POST") throw new HttpError(405, "POST only");

  const body = await req.json();
  const customerId = body?.customer_id;
  const lines = (body?.lines ?? []) as Line[];

  if (!customerId) throw new HttpError(400, "customer_id is required");

  // An empty basket earns nothing, and asking the database to prove it is a
  // round trip the order screen makes on every keystroke.
  if (lines.length === 0) {
    return json({ order_amount: 0, total_discount: 0, earned: [] });
  }

  const clean = lines
    .filter((l) => l.product_id && l.uom_code && (l.qty ?? 0) > 0)
    .map((l) => ({
      product_id: l.product_id,
      uom_code: l.uom_code,
      qty: Math.trunc(l.qty as number),
    }));

  if (clean.length === 0) {
    return json({ order_amount: 0, total_discount: 0, earned: [] });
  }

  const result = unwrap(
    await db.rpc("calculate_promotions", {
      p_customer_id: customerId,
      p_lines: clean,
    }),
  );

  return json(result);
}));
