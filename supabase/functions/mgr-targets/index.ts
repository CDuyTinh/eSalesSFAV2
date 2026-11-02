// =============================================================================
// eSalesManager — Chỉ tiêu nhân viên
//
// GET  /mgr-targets?month=YYYY-MM-DD&salespersonId=…  → the rep's month: overall
//      targets, every product group and product with its target, and whether
//      the caller may change them
// POST /mgr-targets { salesperson_id, month, revenue_target?, order_target?,
//                     categories: [{ id, target }], products: [{ id, target }] }
//
// Who may read or write, and which months, is decided by mgr_targets() and
// mgr_targets_save(); their refusals come back as 409 with the reason.
// =============================================================================

import { handler, HttpError, json, unwrap } from "../_shared/client.ts";

function ruled<T>(result: { data: T | null; error: { code?: string; message: string } | null }) {
  if (result.error?.code === "P0001") throw new HttpError(409, result.error.message);
  return unwrap(result);
}

Deno.serve(handler(async (req, db) => {
  if (req.method === "GET") {
    const params = new URL(req.url).searchParams;
    const month = params.get("month");
    const salespersonId = params.get("salespersonId");
    if (!month || !/^\d{4}-\d{2}-\d{2}$/.test(month)) throw new HttpError(400, "month is required (YYYY-MM-DD)");
    if (!salespersonId) throw new HttpError(400, "salespersonId is required");
    return json(ruled(await db.rpc("mgr_targets", { p_month: month, p_salesperson_id: salespersonId })));
  }

  if (req.method === "POST") {
    const body = await req.json().catch(() => null);
    if (!body?.salesperson_id || !body?.month) throw new HttpError(400, "salesperson_id and month are required");
    ruled(await db.rpc("mgr_targets_save", { payload: body }));
    return json({ ok: true });
  }

  throw new HttpError(405, "GET or POST only");
}));
