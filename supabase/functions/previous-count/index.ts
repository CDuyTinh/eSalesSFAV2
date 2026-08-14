// =============================================================================
// GET /previous-count?customerId=...&exceptVisitId=...
//
// This outlet's last stock count, totalled per product in base units — the "last
// time" figure the rep compares against while counting.
//
// `exceptVisitId` excludes the visit in progress. On a recount the figure worth
// comparing against is the previous visit's, not the attempt being replaced;
// comparing against the latter would report roughly zero sales every time a rep
// corrected themselves. `submit_stock_count` stores prev_base_qty on the same
// rule, so what the rep sees here and what lands in the row agree.
//
// The per-product summing happens here rather than on the device because one
// product may have been counted loose and by the case in the same visit, and only
// the base-unit total compares. That was client-side arithmetic over a payload
// that existed only to be collapsed.
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
  const params = new URL(req.url).searchParams;
  const customerId = params.get("customerId");
  const exceptVisitId = params.get("exceptVisitId");

  if (!customerId) throw new HttpError(400, "customerId is required");
  if (!exceptVisitId) throw new HttpError(400, "exceptVisitId is required");

  const rows = unwrap(
    await db.from("stock_count")
      .select("id,count_date,lines:stock_count_line(product_id,base_qty)")
      .eq("customer_id", customerId)
      .neq("visit_id", exceptVisitId)
      .order("count_date", { ascending: false })
      .order("created_at", { ascending: false })
      .limit(1),
  ) as CountRow[];

  const previous = rows[0];
  if (!previous) {
    // Never counted. Distinct from "counted and everything was zero", which the
    // rep needs to be able to tell apart, so the date comes back null rather than
    // the map coming back empty for both cases.
    return json({ count_date: null, previous: {} });
  }

  const totals: Record<string, number> = {};
  for (const line of previous.lines ?? []) {
    totals[line.product_id] = (totals[line.product_id] ?? 0) + line.base_qty;
  }

  return json({ count_date: previous.count_date, previous: totals });
}));
