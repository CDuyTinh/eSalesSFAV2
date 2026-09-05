// =============================================================================
// GET  /cart?customerId=…   — this rep's basket for one outlet
// POST /cart                — replace it
//
// One function for both because they are one resource, and the pair is only
// useful together: the order step reads the basket when it opens and writes it
// back after every change.
//
// The app this replaces splits them into FetchCartItem, InsertCartItem and
// UpdateCartItem, where insert and update differ only in whether the row already
// existed. `set_order_cart` takes the whole basket and makes the table match, so
// there is nothing for a third endpoint to do.
//
// Lines come back as product and unit ids only. Prices are not stored with the
// basket — the catalogue prices the screen and `submit_order` prices the booking,
// both from the same effective-dated list.
// =============================================================================

import { handler, HttpError, json, unwrap } from "../_shared/client.ts";

interface Item {
  product_id: string;
  uom_code: string;
  qty: number;
}

Deno.serve(handler(async (req, db) => {
  if (req.method === "GET") {
    const customerId = new URL(req.url).searchParams.get("customerId");
    if (!customerId) throw new HttpError(400, "customerId is required");

    // RLS scopes this to the caller's own basket, so no salesperson filter is
    // written here — the same reason /route carries none.
    const items = unwrap(
      await db.from("order_cart")
        .select("product_id,uom_code,qty")
        .eq("customer_id", customerId),
    ) as Item[];

    return json({ customer_id: customerId, items });
  }

  if (req.method === "POST") {
    const body = await req.json() as { customer_id?: string; items?: Item[] };
    if (!body.customer_id) throw new HttpError(400, "customer_id is required");

    // An empty list is a real request: it is how the rep empties the basket.
    const items = body.items ?? [];

    const kept = unwrap(
      await db.rpc("set_order_cart", {
        p_customer_id: body.customer_id,
        p_items: items,
      }),
    ) as number;

    return json({ customer_id: body.customer_id, items: kept });
  }

  throw new HttpError(405, "GET or POST only");
}));
