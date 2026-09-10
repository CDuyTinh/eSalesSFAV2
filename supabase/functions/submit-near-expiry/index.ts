// =============================================================================
// POST /submit-near-expiry
//
// Forwards to `submit_near_expiry_check`. InsertTransDate.
//
// The rules the legacy form applies on the device — a lot number of the
// configured length, no lot entered twice for one product, and a photograph or a
// reason for its absence — are applied again in the function. The client's copy
// tells the rep before they walk away; the server's copy is what makes them true.
//
// The photos are already in storage by the time this is called: the client
// uploads first and passes the object names, as the display and POSM steps do.
// =============================================================================

import { handler, HttpError, json, unwrap } from "../_shared/client.ts";

Deno.serve(handler(async (req, db) => {
  if (req.method !== "POST") throw new HttpError(405, "POST only");

  const body = await req.json();

  if (!body?.id) throw new HttpError(400, "id is required");
  if (!body?.visit_id) throw new HttpError(400, "visit_id is required");
  if (!Array.isArray(body?.lots) || body.lots.length === 0) {
    throw new HttpError(400, "at least one lot is required");
  }

  const checkId = unwrap(
    await db.rpc("submit_near_expiry_check", { p_check: body }),
  );

  return json({ near_expiry_check_id: checkId });
}));
