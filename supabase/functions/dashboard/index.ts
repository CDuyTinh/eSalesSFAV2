// =============================================================================
// GET /dashboard?date=YYYY-MM-DD
//
// The Overview tab in one request.
//
// Thin on purpose: the figures are six aggregates over four tables, and doing
// that here would mean six round trips to the database and a screen that could
// show today's orders counted at one instant and today's visits at another. The
// whole calculation lives in dashboard_overview() instead, so every number on
// the screen comes from a single consistent read.
//
// The date comes from the caller rather than current_date, because the rep's
// "today" is their phone's today. A rep working past midnight, or in a timezone
// the database does not share, must still see the day they are standing in.
// =============================================================================

import { handler, HttpError, json, unwrap } from "../_shared/client.ts";

Deno.serve(handler(async (req, db) => {
  const date = new URL(req.url).searchParams.get("date");
  if (!date) throw new HttpError(400, "date is required (YYYY-MM-DD)");

  const parsed = new Date(`${date}T00:00:00Z`);
  if (Number.isNaN(parsed.getTime())) {
    throw new HttpError(400, `date is not a valid date: ${date}`);
  }

  return json(unwrap(await db.rpc("dashboard_overview", { p_date: date })));
}));
