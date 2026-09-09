// =============================================================================
// POST /submit-posm-registration
//
// Forwards to `submit_posm_registration`. InsertPosmRegis.
//
// The rep asks; head office rules. Nothing in the payload can set the approved
// quantity or the status — the function writes both itself — so a client cannot
// approve its own request by sending a hopeful field.
// =============================================================================

import { handler, HttpError, json, unwrap } from "../_shared/client.ts";

Deno.serve(handler(async (req, db) => {
  if (req.method !== "POST") throw new HttpError(405, "POST only");

  const body = await req.json();

  if (!body?.visit_id) throw new HttpError(400, "visit_id is required");
  if (!Array.isArray(body?.lines) || body.lines.length === 0) {
    throw new HttpError(400, "at least one line is required");
  }

  const registered = unwrap(
    await db.rpc("submit_posm_registration", { p_payload: body }),
  );

  return json({ registered });
}));
