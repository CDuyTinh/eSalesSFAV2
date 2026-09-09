// =============================================================================
// POST /submit-posm-movement
//
// Forwards to `submit_posm_movement`. InsertDeliverPosm, whose OrderType 'IN'
// and 'IR' are this payload's `kind`: delivery and recall.
//
// The photographs are already in storage by the time this is called — the client
// uploads first and passes the object names, as the display and POSM checks do —
// and the function refuses a handover that arrives without one.
// =============================================================================

import { handler, HttpError, json, unwrap } from "../_shared/client.ts";

Deno.serve(handler(async (req, db) => {
  if (req.method !== "POST") throw new HttpError(405, "POST only");

  const body = await req.json();

  if (!body?.visit_id) throw new HttpError(400, "visit_id is required");
  if (body?.kind !== "delivery" && body?.kind !== "recall") {
    throw new HttpError(400, "kind must be delivery or recall");
  }
  if (!Array.isArray(body?.lines) || body.lines.length === 0) {
    throw new HttpError(400, "at least one line is required");
  }

  const movementId = unwrap(
    await db.rpc("submit_posm_movement", { p_payload: body }),
  );

  return json({ posm_movement_id: movementId });
}));
