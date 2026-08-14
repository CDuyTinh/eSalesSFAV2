// =============================================================================
// GET /visit-count?visitId=...
//
// What this visit's stock count found, totalled per product in base units.
//
// Distinct from /previous-count, which deliberately excludes the visit in
// progress. This one is that visit and nothing else: it is what the order screen
// needs in order to say how far below par the shelf is right now.
//
// The endpoint stops here rather than returning suggestions. Resolving which SKUs
// are required, subtracting, and converting base units into whole cases are all
// rules, and they live in :domain where they are unit tested — this machine cannot
// run the TypeScript at all. So the function returns the one fact the client
// cannot derive for itself and nothing more.
// =============================================================================

import { handler, HttpError, json, unwrap } from "../_shared/client.ts";

interface CountLine {
  product_id: string;
  base_qty: number;
}

interface CountRow {
  id: string;
  count_date: string;
  lines: CountLine[] | null;
}

Deno.serve(handler(async (req, db) => {
  const visitId = new URL(req.url).searchParams.get("visitId");
  if (!visitId) throw new HttpError(400, "visitId is required");

  // At most one row: stock_count is unique on visit_id, because a recount replaces
  // the visit's earlier attempt rather than adding to it.
  const rows = unwrap(
    await db.from("stock_count")
      .select("id,count_date,lines:stock_count_line(product_id,base_qty)")
      .eq("visit_id", visitId)
      .limit(1),
  ) as CountRow[];

  const count = rows[0];
  if (!count) {
    // Not counted yet on this visit. Null rather than an empty map, so the client
    // can tell "no count" from "counted and everything was zero" — the same
    // distinction the count itself is careful about.
    return json({ visit_id: visitId, count_date: null, counted: {} });
  }

  const totals: Record<string, number> = {};
  for (const line of count.lines ?? []) {
    totals[line.product_id] = (totals[line.product_id] ?? 0) + line.base_qty;
  }

  return json({ visit_id: visitId, count_date: count.count_date, counted: totals });
}));
