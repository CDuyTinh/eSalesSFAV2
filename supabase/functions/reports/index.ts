// =============================================================================
// GET /reports?activities=YYYY-MM-DD
// GET /reports?sales=YYYY-MM-DD
//
// Thin: both reports are assembled in SQL next to the rows, and this only picks
// which question was asked and hands back the answer.
//
// One function for two reports because there is nothing to either of them but
// the rpc call. Two deployments to maintain, for two lines of routing, would be
// two chances for one to drift out of step with the shared client.
// =============================================================================

import { handler, HttpError, json, unwrap } from "../_shared/client.ts";

Deno.serve(handler(async (req, db) => {
  if (req.method !== "GET") throw new HttpError(405, "GET only");

  const params = new URL(req.url).searchParams;
  const activities = params.get("activities");
  const sales = params.get("sales");

  if (activities && sales) {
    throw new HttpError(400, "ask for one report, not both");
  }

  if (activities) {
    return json(unwrap(await db.rpc("report_activities", { p_date: activities })));
  }

  if (sales) {
    // Any day in the month; the function truncates. The client sends the first,
    // but a date in the middle must not silently return a different month's
    // figures, so the truncation lives on one side only.
    return json(unwrap(await db.rpc("report_sales", { p_month: sales })));
  }

  throw new HttpError(400, "activities or sales is required (YYYY-MM-DD)");
}));
