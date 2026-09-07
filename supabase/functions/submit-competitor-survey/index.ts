// =============================================================================
// POST /submit-competitor-survey
//
// Forwards to `submit_competitor_survey`. InsertAnswersCompetitorSurvey, which
// over there took one answer at a time; here the whole grid travels at once,
// because a half-filed grid is not a state the rep ever meant to leave behind.
//
// Photos are already in storage by the time this is called — the client uploads
// first and passes the object names, as the POSM and display steps do.
//
// The function marks the step finished only when every survey the outlet owes
// today has been answered, so a reply of "filed" is not a reply of "step done".
// =============================================================================

import { handler, HttpError, json, unwrap } from "../_shared/client.ts";

Deno.serve(handler(async (req, db) => {
  if (req.method !== "POST") throw new HttpError(405, "POST only");

  const body = await req.json();

  if (!body?.visit_id) throw new HttpError(400, "visit_id is required");
  if (!body?.survey_id) throw new HttpError(400, "survey_id is required");

  const filed = unwrap(
    await db.rpc("submit_competitor_survey", { p_payload: body }),
  );

  return json({ answers_filed: filed });
}));
