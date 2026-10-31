// =============================================================================
// eSalesManager — Lập tuyến (MCP) & phân tuyến
//
// GET  /mgr-routes                          → the team's routes
// GET  /mgr-routes?id=<routeId>             → outlets, schedule, history
// GET  /mgr-routes?candidates=<routeId>&query=… → outlets that could join it
// POST /mgr-routes
//        { action: "set",    route_id, customer_id, weekdays: [1..7], weeks?: [1..4] }
//        { action: "remove", route_id, customer_id }
//        { action: "assign", route_id, salesperson_id, note? }
//        { action: "reorder", route_id, customer_ids: [...] }   (the whole route, in call order)
//
// Legacy McpController + RouteAssignmentController. The database functions
// check that the route is run by someone in the caller's team.
// =============================================================================

import { handler, HttpError, json, unwrap } from "../_shared/client.ts";

function ruled<T>(result: { data: T | null; error: { code?: string; message: string } | null }) {
  if (result.error?.code === "P0001") throw new HttpError(409, result.error.message);
  return unwrap(result);
}

interface Body {
  action?: string;
  route_id?: string;
  customer_id?: string;
  salesperson_id?: string;
  weekdays?: number[];
  weeks?: number[];
  note?: string;
  customer_ids?: string[];
}

Deno.serve(handler(async (req, db) => {
  if (req.method === "GET") {
    const params = new URL(req.url).searchParams;
    const id = params.get("id");
    if (id) return json(ruled(await db.rpc("mgr_route_detail", { p_route_id: id })));

    const candidates = params.get("candidates");
    if (candidates) {
      return json(ruled(await db.rpc("mgr_route_candidates", {
        p_route_id: candidates, p_query: params.get("query"),
      })));
    }
    return json(unwrap(await db.rpc("mgr_routes")));
  }

  if (req.method === "POST") {
    const body = await req.json() as Body;
    if (!body.route_id) throw new HttpError(400, "route_id is required");

    switch (body.action) {
      case "set":
        if (!body.customer_id) throw new HttpError(400, "customer_id is required");
        return json(ruled(await db.rpc("mgr_route_set_customer", {
          p_route_id: body.route_id,
          p_customer_id: body.customer_id,
          p_weekdays: body.weekdays ?? [],
          p_weeks: body.weeks ?? [],
        })));

      case "remove":
        if (!body.customer_id) throw new HttpError(400, "customer_id is required");
        return json(ruled(await db.rpc("mgr_route_remove_customer", {
          p_route_id: body.route_id, p_customer_id: body.customer_id,
        })));

      case "reorder":
        if (!body.customer_ids?.length) throw new HttpError(400, "customer_ids is required");
        return json({
          changed: ruled(await db.rpc("mgr_route_reorder", {
            p_route_id: body.route_id, p_customer_ids: body.customer_ids,
          })),
        });

      case "assign":
        if (!body.salesperson_id) throw new HttpError(400, "salesperson_id is required");
        return json(ruled(await db.rpc("mgr_route_assign", {
          p_route_id: body.route_id, p_salesperson_id: body.salesperson_id, p_note: body.note ?? null,
        })));
    }
    throw new HttpError(400, `unknown action: ${body.action}`);
  }

  throw new HttpError(405, "GET or POST only");
}));
