// =============================================================================
// eSalesManager — Tổng quan
//
// GET /mgr-dashboard?date=YYYY-MM-DD[&salespersonId=…]
//
// The team's day and month, the per-rep ranking and the sales line, for the
// supervisor's first tab. Legacy OverviewController (API_DashboardOverview_Manager
// + API_DashboardOverviewChart_Manager) in one call. mgr_dashboard() scopes to
// the caller's team; naming a rep outside it is refused.
// =============================================================================

import { handler, HttpError, json, unwrap } from "../_shared/client.ts";

Deno.serve(handler(async (req, db) => {
  if (req.method !== "GET") throw new HttpError(405, "GET only");

  const params = new URL(req.url).searchParams;
  const date = params.get("date");
  if (!date || !/^\d{4}-\d{2}-\d{2}$/.test(date)) {
    throw new HttpError(400, "date is required (YYYY-MM-DD)");
  }

  const result = await db.rpc("mgr_dashboard", {
    p_date: date,
    p_salesperson_id: params.get("salespersonId") || null,
    p_supervisor_id: params.get("supervisorId") || null,
  });
  if (result.error?.code === "P0001") throw new HttpError(403, result.error.message);

  return json(unwrap(result));
}));
