// =============================================================================
// POST /submit-survey
//
// Forwards to the `submit_survey` database function.
//
// Thin, like the other write functions. The scoring, the required-question check and
// the visit_step_result write are one transaction, and the scores in particular have
// to be computed next to the question definitions they come from — a client that can
// name its own score is a client that can pass an audit it failed.
//
// One endpoint serves every questionnaire step. The payload's `form_id` selects the
// questionnaire, so `posm_status`, `market_info` and anything added later all arrive
// here.
// =============================================================================

import { handler, HttpError, json, unwrap } from "../_shared/client.ts";

Deno.serve(handler(async (req, db) => {
  if (req.method !== "POST") throw new HttpError(405, "POST only");

  const survey = await req.json();
  const surveyId = unwrap(await db.rpc("submit_survey", { p_survey: survey }));

  return json({ survey_id: surveyId });
}));
