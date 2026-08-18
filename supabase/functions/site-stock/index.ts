// =============================================================================
// GET /site-stock            -> the branch's first warehouse and its stock
// GET /site-stock?site_id=…  -> that warehouse instead
//
// Read-only. Nothing in the app writes stock: this app is not the warehouse
// system, and a figure a rep could edit is a figure nobody can trust.
// =============================================================================

import { handler, HttpError, json, unwrap } from "../_shared/client.ts";

Deno.serve(handler(async (req, db) => {
  if (req.method !== "GET") throw new HttpError(405, "GET only");

  const siteId = new URL(req.url).searchParams.get("site_id");

  return json(unwrap(await db.rpc("site_stock_list", { p_site_id: siteId })));
}));
