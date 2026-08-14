// =============================================================================
// GET /visit-workflow?visitId=...
//
// What the server knows about this visit's progress: which steps are recorded as
// done, and when.
//
// Only completions. The step definitions are not repeated here even though it
// would be one more line to add them — the client caches them from /bootstrap so
// the step list renders with no signal, and re-sending them on every visit load
// would be payload that is thrown away every time.
//
// This deliberately does not return a finished workflow either. The client still
// merges in the completions sitting in its own outbox, because a step finished in
// a dead spot exists nowhere else, and that merge along with the prerequisite
// rules lives in :domain where it is unit tested. Shaping it here would move
// tested logic into code that cannot be run locally at all.
// =============================================================================

import { handler, HttpError, json, unwrap } from "../_shared/client.ts";

Deno.serve(handler(async (req, db) => {
  const visitId = new URL(req.url).searchParams.get("visitId");
  if (!visitId) throw new HttpError(400, "visitId is required");

  const completions = unwrap(
    await db.from("visit_step_result")
      .select("form_id,completed_at")
      .eq("visit_id", visitId),
  );

  return json({ visit_id: visitId, completions });
}));
