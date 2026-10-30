// =============================================================================
// eSalesManager — Đi tuyến cùng nhân viên
//
// GET  /mgr-work-with?rep=<salespersonId>&date=YYYY-MM-DD → the rep's stops
// GET  /mgr-work-with?visit=<visitId>                     → one call, programmes to score
// POST /mgr-work-with
//        { action: "check_in",  rep_id, customer_id, lat?, lng?, accuracy_m?, note? }
//        { action: "check_out", visit_id, note?, lat?, lng? }
//        { action: "display",   audit: { id, visit_id, program_id, level_id,
//                                        counted_faces, achieved, note?, photos: [...] } }
//
// The supervisor's call is their own visit (visit.work_with_salesperson_id names
// the rep), which is why scoring a display can go straight through SFA's
// submit_display_audit: it accepts any open visit of the caller's.
// =============================================================================

import { handler, HttpError, json, unwrap } from "../_shared/client.ts";

function ruled<T>(result: { data: T | null; error: { code?: string; message: string } | null }) {
  if (result.error?.code === "P0001") throw new HttpError(409, result.error.message);
  return unwrap(result);
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
  audit?: Record<string, unknown>;
}

Deno.serve(handler(async (req, db) => {
  if (req.method === "GET") {
    const params = new URL(req.url).searchParams;

    const visit = params.get("visit");
    if (visit) {
      return json(ruled(await db.rpc("mgr_work_with_visit", { p_visit_id: visit })));
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
        const id = ruled(await db.rpc("submit_display_audit", { p_audit: body.audit }));
        return json({ id });
      }
    }
    throw new HttpError(400, `unknown action: ${body.action}`);
  }

  throw new HttpError(405, "GET or POST only");
}));
