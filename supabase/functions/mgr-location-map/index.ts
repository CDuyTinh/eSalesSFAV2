// =============================================================================
// eSalesManager — Bản đồ vị trí nhân viên (legacy OM30400)
//
// GET /mgr-location-map?date=YYYY-MM-DD[&salespersonId=…]
//
// Each rep's day: the path the tracking service recorded, the outlets they
// checked in at, and their clock-in / clock-out. Team-scoped by
// mgr_location_map() itself.
// =============================================================================

import { handler, HttpError, json, unwrap } from "../_shared/client.ts";

Deno.serve(handler(async (req, db) => {
  if (req.method !== "GET") throw new HttpError(405, "GET only");
  const params = new URL(req.url).searchParams;
  const date = params.get("date");
  if (!date || !/^\d{4}-\d{2}-\d{2}$/.test(date)) throw new HttpError(400, "date is required (YYYY-MM-DD)");
  const result = await db.rpc("mgr_location_map", { p_date: date, p_rep: params.get("salespersonId") || null });
  if (result.error?.code === "P0001") throw new HttpError(403, result.error.message);
  return json({ date, reps: unwrap(result) });
}));
