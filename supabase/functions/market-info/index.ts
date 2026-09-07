// =============================================================================
// GET /market-info?customerId=…&visitId=…
// GET /market-info?customerId=…&visitId=…&surveyId=…   (one competitor survey)
//
// The step opens a list, not a questionnaire. API_GetMarketInfo unions the two
// kinds of survey live for the branch today — the market questionnaires and the
// competitor surveys — and marks each finished or not; the rep picks one.
//
// Without surveyId this returns that list. With it, the competitor survey named
// is returned in full: its criteria, its product pairings, and whatever this
// visit has already answered, so reopening one shows the earlier answers rather
// than a blank grid.
//
// The questionnaire half needs no detail call. Its questions come down with the
// rest of the survey definitions the app already caches.
//
// visitId keys the answers rather than the date, as everywhere else in the call:
// a shop called on twice starts the second call with its surveys unanswered.
// =============================================================================

import { handler, HttpError, json, unwrap } from "../_shared/client.ts";

interface SurveyRow {
  kind: "market" | "competitor";
  id: string;
  code: string;
  name: string;
  from_date: string | null;
  to_date: string | null;
  is_completed: boolean;
  item_count: number;
}

Deno.serve(handler(async (req, db) => {
  if (req.method !== "GET") throw new HttpError(405, "GET only");

  const params = new URL(req.url).searchParams;
  const customerId = params.get("customerId");
  const visitId = params.get("visitId");
  const surveyId = params.get("surveyId");

  if (!visitId) throw new HttpError(400, "visitId is required");

  if (surveyId) {
    const detail = unwrap(
      await db.rpc("competitor_survey_detail", {
        p_survey_id: surveyId,
        p_visit_id: visitId,
      }),
    );

    // Null means no such survey, which for the client is a survey that ended
    // between listing it and opening it — a 404 rather than an empty grid.
    if (!detail) throw new HttpError(404, "no such competitor survey");

    return json(detail);
  }

  if (!customerId) throw new HttpError(400, "customerId is required");

  const surveys = unwrap(
    await db.rpc("market_info_surveys", {
      p_customer_id: customerId,
      p_visit_id: visitId,
    }),
  ) as SurveyRow[];

  return json({
    customer_id: customerId,
    visit_id: visitId,
    // Empty is a real answer: a branch running no surveys today leaves the step
    // with nothing to do, and the screen says so rather than spinning.
    surveys,
  });
}));
