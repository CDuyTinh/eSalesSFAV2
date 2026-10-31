// =============================================================================
// eSalesManager — Sửa khách hàng theo nhân viên
//
// GET  /mgr-customers?rep=<salespersonId>[&query=…]  → the rep's outlets
// GET  /mgr-customers?id=<customerId>                → editable fields, options, history
// POST /mgr-customers  { id, changes: { name?, contact_name?, phone?, address?,
//                        class_id?, channel_id?, shop_type_id?, lat?, lng? } }
//
// Legacy API_EditCust_Manager_GetCustomerList / API_UpdateCustomer_EmployeeManager.
// The database functions check the outlet is on a route of the caller's team.
// =============================================================================

import { handler, HttpError, json, unwrap } from "../_shared/client.ts";

function ruled<T>(result: { data: T | null; error: { code?: string; message: string } | null }) {
  if (result.error?.code === "P0001") throw new HttpError(409, result.error.message);
  return unwrap(result);
}

Deno.serve(handler(async (req, db) => {
  if (req.method === "GET") {
    const params = new URL(req.url).searchParams;
    const id = params.get("id");
    if (id) return json(ruled(await db.rpc("mgr_customer_edit", { p_customer_id: id })));
    const rep = params.get("rep");
    if (!rep) throw new HttpError(400, "rep or id is required");
    return json(ruled(await db.rpc("mgr_team_customers", { p_rep: rep, p_query: params.get("query") })));
  }

  if (req.method === "POST") {
    const body = await req.json() as { id?: string; changes?: Record<string, unknown> };
    if (!body.id) throw new HttpError(400, "id is required");
    return json(ruled(await db.rpc("mgr_update_customer", {
      p_customer_id: body.id, payload: body.changes ?? {},
    })));
  }

  throw new HttpError(405, "GET or POST only");
}));
