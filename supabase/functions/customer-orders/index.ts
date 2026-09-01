// =============================================================================
// GET /customer-orders?customer_id=…&limit=…
//
// The outlet's recent orders, lines included.
//
// Its own function rather than more fields on /customer-info because the two are
// read at different times: the detail card opens with the screen, this one only
// when the rep moves to the history tab. Bundling them would make every open of
// the screen pay for a tab most visits never touch.
// =============================================================================

import { handler, HttpError, json, unwrap } from "../_shared/client.ts";

const DEFAULT_LIMIT = 20;
const MAX_LIMIT = 100;

Deno.serve(handler(async (req, db) => {
  if (req.method !== "GET") throw new HttpError(405, "GET only");

  const params = new URL(req.url).searchParams;
  const customerId = params.get("customer_id");
  if (!customerId) throw new HttpError(400, "customer_id is required");

  // Clamped rather than trusted: the limit reaches the database directly, and an
  // absurd one is a slow query for everybody on the instance, not just the caller.
  const asked = Number(params.get("limit") ?? DEFAULT_LIMIT);
  const limit = Number.isFinite(asked)
    ? Math.min(Math.max(Math.trunc(asked), 1), MAX_LIMIT)
    : DEFAULT_LIMIT;

  const orders = unwrap(
    await db.rpc("customer_order_history", {
      p_customer_id: customerId,
      p_limit: limit,
    }),
  );

  // An outlet that has never ordered is a normal answer, not a 404 — unlike
  // /customer-info, where no row means the outlet is somebody else's.
  return json({ orders: orders ?? [] });
}));
