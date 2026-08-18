// =============================================================================
// GET /focus-products?date=YYYY-MM-DD
//
// What head office is pushing on that day, with this rep's progress against it.
// Read-only: nothing in the app creates or edits a focus product.
// =============================================================================

import { handler, HttpError, json, unwrap } from "../_shared/client.ts";

Deno.serve(handler(async (req, db) => {
  if (req.method !== "GET") throw new HttpError(405, "GET only");

  const date = new URL(req.url).searchParams.get("date");
  if (!date) throw new HttpError(400, "date is required (YYYY-MM-DD)");

  return json({ date, products: unwrap(await db.rpc("focus_products", { p_date: date })) });
}));
