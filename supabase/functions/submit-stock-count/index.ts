// =============================================================================
// POST /submit-stock-count
//
// Forwards to the `submit_stock_count` database function.
//
// Thin for the same reason as /submit-order: the header, the lines, the previous
// figures and the `stock_outlet` step are one transaction, and the recount-replaces
// behaviour depends on the delete happening before the previous figures are read.
// None of that survives being split across HTTP calls.
// =============================================================================

import { handler, HttpError, json, unwrap } from "../_shared/client.ts";

Deno.serve(handler(async (req, db) => {
  if (req.method !== "POST") throw new HttpError(405, "POST only");

  const count = await req.json();
  const countId = unwrap(await db.rpc("submit_stock_count", { p_count: count }));

  return json({ stock_count_id: countId });
}));
