// =============================================================================
// GET /near-expiry?visitId=…
//
// What this visit has already written down about stock going off, so reopening
// the step shows the lots the rep entered rather than a blank list they would
// key in twice.
//
// Null when nothing has been filed, which the client tells apart from an empty
// document: "not started" and "walked the shelf and found nothing" are different
// answers, and only the first should reopen on an empty form.
// =============================================================================

import { handler, HttpError, json, unwrap } from "../_shared/client.ts";

Deno.serve(handler(async (req, db) => {
  if (req.method !== "GET") throw new HttpError(405, "GET only");

  const visitId = new URL(req.url).searchParams.get("visitId");
  if (!visitId) throw new HttpError(400, "visitId is required");

  const check = unwrap(await db.rpc("near_expiry_for", { p_visit_id: visitId }));

  return json({ visit_id: visitId, check });
}));
