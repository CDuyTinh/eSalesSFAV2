// =============================================================================
// eSalesManager — Huấn luyện EDAC
//
// GET  /mgr-edac?date=YYYY-MM-DD                  → the supervisor's coaching plans that day
// GET  /mgr-edac?id=<trainingId>                  → one coaching day, KPIs, photos signed
// GET  /mgr-edac?id=<trainingId>&step=N[&customer=<id>] → a step's outlets, or its form
// GET  /mgr-edac?history=1&from=&to=[&rep=]        → past coaching days
// GET  /mgr-edac?result=<trainingId>              → every answer, photos signed
// GET  /mgr-edac?criteria=1                        → the criteria and whether the caller may edit
// POST /mgr-edac
//        { action: "start",  rep_id? , plan_id? }                    → { id }
//        { action: "save",   id, step, customer_id?, answers?, note?, finish? }
//        { action: "finish", id, result, supervisor_photo, salesperson_photo }
//        { action: "criterion", criterion: { id?, step, content, kind, kpi?, required, active, options } }
//
// Legacy EDACTrainingController. The database functions check that the day is
// the caller's own and the rep is in their team.
// =============================================================================

import { handler, HttpError, json, unwrap } from "../_shared/client.ts";
import type { SupabaseClient } from "jsr:@supabase/supabase-js@2";

function ruled<T>(result: { data: T | null; error: { code?: string; message: string } | null }) {
  if (result.error?.code === "P0001") throw new HttpError(409, result.error.message);
  return unwrap(result);
}

function date(value: string | null): string {
  if (!value || !/^\d{4}-\d{2}-\d{2}$/.test(value)) throw new HttpError(400, `${value} is not a date`);
  return value;
}

/** The two closing photos live in the supervisor's own folder, which they may read. */
async function signPhotos(db: SupabaseClient, day: Record<string, unknown>) {
  const sign = async (path: unknown) => {
    if (typeof path !== "string") return null;
    const { data } = await db.storage.from("visit-photos").createSignedUrl(path, 60 * 60);
    return data?.signedUrl ?? null;
  };
  const [sup, rep] = await Promise.all([sign(day.supervisor_photo), sign(day.salesperson_photo)]);
  return { ...day, supervisor_photo_url: sup, salesperson_photo_url: rep };
}

interface Body {
  action?: string;
  id?: string;
  rep_id?: string;
  plan_id?: string;
  step?: number;
  customer_id?: string;
  answers?: unknown[];
  note?: string;
  finish?: boolean;
  result?: string;
  supervisor_photo?: string;
  salesperson_photo?: string;
  criterion?: Record<string, unknown>;
}

Deno.serve(handler(async (req, db) => {
  if (req.method === "GET") {
    const params = new URL(req.url).searchParams;

    if (params.get("criteria")) return json(unwrap(await db.rpc("mgr_edac_criteria")));

    const result = params.get("result");
    if (result) {
      const day = ruled(await db.rpc("mgr_edac_result", { p_id: result })) as Record<string, unknown>;
      return json(await signPhotos(db, day));
    }

    if (params.get("history")) {
      return json(unwrap(await db.rpc("mgr_edac_history", {
        p_from: date(params.get("from")),
        p_to: date(params.get("to")),
        p_rep: params.get("rep"),
      })));
    }

    const id = params.get("id");
    if (id) {
      const step = params.get("step");
      if (step !== null) {
        return json(ruled(await db.rpc("mgr_edac_step", {
          p_id: id, p_step: Number(step), p_customer: params.get("customer"),
        })));
      }
      const day = ruled(await db.rpc("mgr_edac_training", { p_id: id })) as Record<string, unknown>;
      return json(await signPhotos(db, day));
    }

    return json(unwrap(await db.rpc("mgr_edac_sessions", { p_date: date(params.get("date")) })));
  }

  if (req.method === "POST") {
    const body = await req.json() as Body;
    switch (body.action) {
      case "start":
        return json({
          id: ruled(await db.rpc("mgr_edac_start", {
            p_rep: body.rep_id ?? null, p_plan_id: body.plan_id ?? null,
          })),
        });

      case "save":
        if (!body.id || body.step === undefined) throw new HttpError(400, "id and step are required");
        return json(ruled(await db.rpc("mgr_edac_save", {
          p_id: body.id,
          p_step: body.step,
          p_customer: body.customer_id ?? null,
          p_answers: body.answers ?? [],
          p_note: body.note ?? null,
          p_finish: body.finish ?? false,
        })));

      case "criterion":
        if (!body.criterion) throw new HttpError(400, "criterion is required");
        return json({ id: ruled(await db.rpc("mgr_edac_save_criterion", { payload: body.criterion })) });

      case "finish":
        if (!body.id) throw new HttpError(400, "id is required");
        ruled(await db.rpc("mgr_edac_finish", {
          p_id: body.id,
          p_result: body.result ?? null,
          p_supervisor_photo: body.supervisor_photo ?? null,
          p_salesperson_photo: body.salesperson_photo ?? null,
        }));
        return json({ ok: true });
    }
    throw new HttpError(400, `unknown action: ${body.action}`);
  }

  throw new HttpError(405, "GET or POST only");
}));
