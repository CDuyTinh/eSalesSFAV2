// =============================================================================
// POST /submit-loyalty-registration
//
// Forwards to `submit_loyalty_registration`. InsertTradeRegis, TradeType 'A'.
//
// The rep signs the outlet up at a band; head office rules on it. The status is
// the function's to write and the slot comes out of the rep's own allocation, so
// nothing in this body can approve anything.
// =============================================================================

import { handler, HttpError, json, unwrap } from "../_shared/client.ts";

Deno.serve(handler(async (req, db) => {
  if (req.method !== "POST") throw new HttpError(405, "POST only");

  const body = await req.json();

  if (!body?.visit_id) throw new HttpError(400, "visit_id is required");
  if (!body?.program_id) throw new HttpError(400, "program_id is required");
  if (!body?.level_id) throw new HttpError(400, "level_id is required");

  const registrationId = unwrap(
    await db.rpc("submit_loyalty_registration", { p_payload: body }),
  );

  return json({ loyalty_registration_id: registrationId });
}));
