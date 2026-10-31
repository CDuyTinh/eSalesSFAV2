// =============================================================================
// eSalesManager — Trọng tâm bán hàng
//
// GET /mgr-sales-focus?date=YYYY-MM-DD
//
// Legacy prepare_sales_focus: the focus products running that day with the
// team's progress on each, rep by rep, and the display / loyalty programmes
// running with how many of the team's outlets signed up. Scoped to the
// caller's team by mgr_sales_focus() itself.
// =============================================================================

import { handler, HttpError, json, unwrap } from "../_shared/client.ts";

Deno.serve(handler(async (req, db) => {
  if (req.method !== "GET") throw new HttpError(405, "GET only");
  const date = new URL(req.url).searchParams.get("date");
  if (!date || !/^\d{4}-\d{2}-\d{2}$/.test(date)) throw new HttpError(400, "date is required (YYYY-MM-DD)");
  return json(unwrap(await db.rpc("mgr_sales_focus", { p_date: date })));
}));
