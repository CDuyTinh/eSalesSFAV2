// =============================================================================
// POST /submit-order
//
// Forwards to the `submit_order` database function.
//
// Deliberately a thin pass-through. The pricing, the header/lines/step writes and
// the idempotency all belong in one Postgres transaction, and moving any of it up
// here would mean several round trips that can fail separately — an order with no
// lines, or a step ticked for an order that never landed. What this adds is a
// uniform API surface: the app talks to /functions/v1 for everything.
//
// The body is passed through untouched, so the function's own guards stay the
// authority on what a valid order is. Their messages come back as-is.
// =============================================================================

import { handler, HttpError, json, unwrap } from "../_shared/client.ts";

Deno.serve(handler(async (req, db) => {
  if (req.method !== "POST") throw new HttpError(405, "POST only");

  const order = await req.json();
  const orderId = unwrap(await db.rpc("submit_order", { p_order: order }));

  return json({ order_id: orderId });
}));
