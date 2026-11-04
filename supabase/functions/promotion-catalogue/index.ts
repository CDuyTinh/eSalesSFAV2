// =============================================================================
// GET /promotion-catalogue[?date=YYYY-MM-DD]
//
// Chương trình khuyến mãi: every promotion running for the rep's branch, with
// its levels, the products it counts, its gifts and its budget (the legacy's
// api/TradePromotion/FetchTradePromotion).
// =============================================================================

import { handler, HttpError, json, unwrap } from "../_shared/client.ts";

Deno.serve(handler(async (req, db) => {
  if (req.method !== "GET") throw new HttpError(405, "GET only");
  const date = new URL(req.url).searchParams.get("date");
  if (date && !/^\d{4}-\d{2}-\d{2}$/.test(date)) throw new HttpError(400, "date must be YYYY-MM-DD");
  return json(unwrap(await db.rpc("promotion_catalogue", date ? { p_date: date } : {})));
}));
