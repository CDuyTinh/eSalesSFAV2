// =============================================================================
// eSalesManager — Đi tuyến cùng nhân viên
//
// GET  /mgr-work-with?rep=<salespersonId>&date=YYYY-MM-DD → the rep's stops
// GET  /mgr-work-with?visit=<visitId>                     → one call, programmes to score,
//                                                           and what has been done on it
// GET  /mgr-work-with?visit=<visitId>&products=1          → priced products, previous count
// GET  /mgr-work-with?visit=<visitId>&posm=1              → POSM the outlet holds
// GET  /mgr-work-with?visit=<visitId>&surveys=1           → competitor / market surveys running
// GET  /mgr-work-with?visit=<visitId>&survey=<surveyId>   → one competitor survey's grid
// POST /mgr-work-with
//        { action: "check_in",    rep_id, customer_id, lat?, lng?, accuracy_m?, note? }
//        { action: "check_out",   visit_id, note?, lat?, lng? }
//        { action: "display",     audit: { … } }                → submit_display_audit
//        { action: "stock_count", count: { … } }                → submit_stock_count
//        { action: "preview",     visit_id, lines: [...] }      → calculate_promotions, as the rep
//        { action: "order",       order: { … } }                → mgr_submit_order (the rep's order)
//        { action: "posm_check",  check: { … } }                → submit_posm_check
//        { action: "survey",      survey: { … } }               → submit_competitor_survey
//
// The supervisor's call is their own visit (visit.work_with_salesperson_id names
// the rep), so the SFA functions behind display, stock_count, posm_check and
// survey accept it as they would a rep's: each wants a visit of the caller's.
// Only the order is booked to the rep, through mgr_submit_order.
// =============================================================================

import { handler, HttpError, json, unwrap } from "../_shared/client.ts";
import type { SupabaseClient } from "jsr:@supabase/supabase-js@2";

function ruled<T>(result: { data: T | null; error: { code?: string; message: string } | null }) {
  if (result.error?.code === "P0001") throw new HttpError(409, result.error.message);
  return unwrap(result);
}

interface VisitDetail {
  rep?: { id: string } | null;
  customer?: { id: string } | null;
  [key: string]: unknown;
}

/** The call, which also proves it is the caller's own work-with visit. */
async function visitDetail(db: SupabaseClient, visitId: string): Promise<VisitDetail> {
  return ruled(await db.rpc("mgr_work_with_visit", { p_visit_id: visitId })) as VisitDetail;
}

interface Body {
  action?: string;
  rep_id?: string;
  customer_id?: string;
  visit_id?: string;
  lat?: number;
  lng?: number;
  accuracy_m?: number;
  note?: string;
  lines?: unknown[];
  audit?: Record<string, unknown>;
  count?: Record<string, unknown>;
  order?: Record<string, unknown>;
  check?: Record<string, unknown>;
  survey?: Record<string, unknown>;
}

Deno.serve(handler(async (req, db) => {
  if (req.method === "GET") {
    const params = new URL(req.url).searchParams;

    const visit = params.get("visit");
    if (visit) {
      if (params.get("products")) {
        return json(ruled(await db.rpc("mgr_visit_products", { p_visit_id: visit })));
      }
      const surveyId = params.get("survey");
      if (surveyId) {
        await visitDetail(db, visit);
        return json(ruled(await db.rpc("competitor_survey_detail", { p_survey_id: surveyId, p_visit_id: visit })));
      }
      if (params.get("posm") || params.get("surveys")) {
        const customerId = (await visitDetail(db, visit)).customer?.id;
        if (!customerId) throw new HttpError(404, "visit has no outlet");
        if (params.get("posm")) {
          return json({
            placed: ruled(await db.rpc("posm_at_customer", { p_customer_id: customerId, p_visit_id: visit })) ?? [],
          });
        }
        return json({
          surveys: ruled(await db.rpc("market_info_surveys", { p_customer_id: customerId, p_visit_id: visit })) ?? [],
        });
      }
      const [detail, tasks] = await Promise.all([
        visitDetail(db, visit),
        db.rpc("mgr_visit_tasks", { p_visit_id: visit }).then(ruled),
      ]);
      return json({ ...detail, tasks });
    }

    const rep = params.get("rep");
    const date = params.get("date");
    if (!rep || !date || !/^\d{4}-\d{2}-\d{2}$/.test(date)) {
      throw new HttpError(400, "rep and date (YYYY-MM-DD) are required");
    }
    return json(ruled(await db.rpc("mgr_work_with_route", { p_rep: rep, p_date: date })));
  }

  if (req.method === "POST") {
    const body = await req.json() as Body;

    switch (body.action) {
      case "check_in": {
        if (!body.rep_id || !body.customer_id) throw new HttpError(400, "rep_id and customer_id are required");
        const id = ruled(await db.rpc("mgr_work_with_check_in", {
          payload: {
            rep_id: body.rep_id,
            customer_id: body.customer_id,
            lat: body.lat ?? null,
            lng: body.lng ?? null,
            accuracy_m: body.accuracy_m ?? null,
            note: body.note ?? null,
          },
        }));
        return json({ visit_id: id });
      }

      case "check_out": {
        if (!body.visit_id) throw new HttpError(400, "visit_id is required");
        ruled(await db.rpc("mgr_work_with_check_out", {
          p_visit_id: body.visit_id,
          p_note: body.note ?? null,
          p_lat: body.lat ?? null,
          p_lng: body.lng ?? null,
        }));
        return json({ ok: true });
      }

      case "display": {
        if (!body.audit) throw new HttpError(400, "audit is required");
        return json({ id: ruled(await db.rpc("submit_display_audit", { p_audit: body.audit })) });
      }

      case "stock_count": {
        if (!body.count) throw new HttpError(400, "count is required");
        return json({ id: ruled(await db.rpc("submit_stock_count", { p_count: body.count })) });
      }

      // What the basket would earn, evaluated as the rep: a route budget is the
      // rep's route's, which is what the order will draw from.
      case "preview": {
        if (!body.visit_id) throw new HttpError(400, "visit_id is required");
        const detail = await visitDetail(db, body.visit_id);
        return json(ruled(await db.rpc("calculate_promotions", {
          p_customer_id: detail.customer?.id,
          p_lines: body.lines ?? [],
          p_seller: detail.rep?.id ?? null,
        })));
      }

      case "order": {
        if (!body.order) throw new HttpError(400, "order is required");
        return json({ id: ruled(await db.rpc("mgr_submit_order", { p_order: body.order })) });
      }

      case "posm_check": {
        if (!body.check) throw new HttpError(400, "check is required");
        return json({ id: ruled(await db.rpc("submit_posm_check", { p_check: body.check })) });
      }

      case "survey": {
        if (!body.survey) throw new HttpError(400, "survey is required");
        return json({ id: ruled(await db.rpc("submit_competitor_survey", { p_payload: body.survey })) });
      }
    }
    throw new HttpError(400, `unknown action: ${body.action}`);
  }

  throw new HttpError(405, "GET or POST only");
}));
