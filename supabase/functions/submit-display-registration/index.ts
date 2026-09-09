// =============================================================================
// POST /submit-display-registration
//
// Forwards to `submit_display_registration`. InsertTradeRegis, TradeType 'D'.
//
// The rep signs the outlet up; head office rules on it. Nothing in the payload
// sets the status — the function writes 'pending' itself — and the slot it
// spends comes out of the rep's own allocation.
// =============================================================================

import { handler, HttpError, json, unwrap } from "../_shared/client.ts";

Deno.serve(handler(async (req, db) => {
  if (req.method !== "POST") throw new HttpError(405, "POST only");

  const body = await req.json();

  if (!body?.visit_id) throw new HttpError(400, "visit_id is required");
  if (!body?.program_id) throw new HttpError(400, "program_id is required");
  if (!body?.level_id) throw new HttpError(400, "level_id is required");

  const registrationId = unwrap(
    await db.rpc("submit_display_registration", { p_payload: body }),
  );

  return json({ display_registration_id: registrationId });
}));
