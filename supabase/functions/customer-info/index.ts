// =============================================================================
// GET /customer-info?customer_id=…
//
// One outlet's detail card. Read-only — nothing on this screen is editable, and
// the fields that head office owns (class, channel, credit limit) are not the
// rep's to change from the field.
//
// 404 rather than an empty body when the outlet is invisible: RLS scopes
// `customer` to the rep's own branch, so "no row" here means the id belongs to
// somebody else's territory, which is a different thing from an outlet with no
// details recorded.
// =============================================================================

import { handler, HttpError, json, unwrap } from "../_shared/client.ts";

Deno.serve(handler(async (req, db) => {
  if (req.method !== "GET") throw new HttpError(405, "GET only");

  const customerId = new URL(req.url).searchParams.get("customer_id");
  if (!customerId) throw new HttpError(400, "customer_id is required");

  const info = unwrap(
    await db.rpc("customer_info", { p_customer_id: customerId }),
  );

  if (!info) throw new HttpError(404, "customer not found");

  return json(info);
}));
