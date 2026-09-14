// =============================================================================
// GET /ship-addresses?customerId=…
//
// Where this outlet's goods may be sent. API_GetCustomerAddress.
//
// A route of its own rather than a field on the order screen's other calls: it
// is a different question from "what discounts apply" and it changes on a
// different clock — head office edits an address perhaps twice a year, while
// the promotion preview is refreshed on every basket change.
//
// An empty list is the ordinary answer, not a failure: most outlets receive
// their own goods, and the screen shows the outlet itself in that case.
// =============================================================================

import { handler, HttpError, json, unwrap } from "../_shared/client.ts";

Deno.serve(handler(async (req, db) => {
  if (req.method !== "GET") throw new HttpError(405, "GET only");

  const customerId = new URL(req.url).searchParams.get("customerId");
  if (!customerId) throw new HttpError(400, "customerId is required");

  return json({
    addresses: unwrap(
      await db.rpc("ship_addresses_for", { p_customer_id: customerId }),
    ),
  });
}));
