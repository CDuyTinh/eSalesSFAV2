// =============================================================================
// POST /submit-feedback
//
// Forwards to the `submit_feedback` database function.
//
// Thin for the same reason as the other write endpoints: the feedback row and the
// `feedback` step are one transaction, and the three checks that make the row worth
// storing — the step's own note_min_length, the topic actually being a feedback
// topic, and the audio object existing — belong next to the data they check.
//
// Any audio is already in storage by the time this is called. The client uploads
// first and passes the object name, because the function would otherwise refuse a
// path that is not there yet.
// =============================================================================

import { handler, HttpError, json, unwrap } from "../_shared/client.ts";

Deno.serve(handler(async (req, db) => {
  if (req.method !== "POST") throw new HttpError(405, "POST only");

  const feedback = await req.json();
  const id = unwrap(await db.rpc("submit_feedback", { p_feedback: feedback }));

  return json({ feedback_id: id });
}));
