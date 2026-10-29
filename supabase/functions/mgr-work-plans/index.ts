// =============================================================================
// eSalesManager — Giao việc / công việc của nhóm
//
// GET  /mgr-work-plans?fromDate=&toDate=&statuses=&salespersonId=  → team list
// GET  /mgr-work-plans?id=…                                         → one plan, photos signed
// GET  /mgr-work-plans?categories=1                                 → work types
// GET  /mgr-work-plans?customersOf=<salespersonId>&query=…          → object picker
// POST /mgr-work-plans
//        { action: "assign", plan: { salesperson_id, category_id, content,
//                                    note?, place?, from_at, to_at, objects: [{id}] } }
//        { action: "decide", id, approve, reason? }
//        { action: "cancel", id, reason }
//
// Legacy MyTaskController + TaskApproveController behind one route. Every
// database function called here checks the caller's team itself.
// =============================================================================

import { handler, HttpError, json, unwrap } from "../_shared/client.ts";
import type { SupabaseClient } from "jsr:@supabase/supabase-js@2";

const STATUSES = ["draft", "submitted", "approved", "rejected", "completed", "cancelled"];

function list(value: string | null, allowed: string[]): string[] | null {
  if (!value) return null;
  const parts = value.split(",").map((s) => s.trim()).filter((s) => allowed.includes(s));
  return parts.length > 0 ? parts : null;
}

function date(value: string | null): string | undefined {
  if (!value) return undefined;
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value)) throw new HttpError(400, `${value} is not a date`);
  return value;
}

async function signPhotos(db: SupabaseClient, detail: Record<string, unknown>) {
  const photos = detail.photos as Array<Record<string, unknown>> | undefined;
  if (!photos?.length) return detail;
  const signed = await Promise.all(photos.map(async (photo) => {
    const { data } = await db.storage.from("visit-photos")
      .createSignedUrl(photo.storage_path as string, 60 * 60);
    return { ...photo, url: data?.signedUrl ?? null };
  }));
  return { ...detail, photos: signed };
}

/** A refusal raised in plpgsql is an answer for the person, not a server fault. */
function ruled<T>(result: { data: T | null; error: { code?: string; message: string } | null }) {
  if (result.error?.code === "P0001") throw new HttpError(409, result.error.message);
  return unwrap(result);
}

interface Body {
  action?: string;
  id?: string;
  approve?: boolean;
  reason?: string;
  plan?: Record<string, unknown>;
}

Deno.serve(handler(async (req, db) => {
  if (req.method === "GET") {
    const params = new URL(req.url).searchParams;

    const id = params.get("id");
    if (id) {
      const detail = unwrap(await db.rpc("mgr_work_plan_detail", { p_plan_id: id })) as
        | Record<string, unknown>
        | null;
      if (!detail) throw new HttpError(404, "không tìm thấy kế hoạch trong nhóm của bạn");
      return json(await signPhotos(db, detail));
    }

    if (params.get("categories")) {
      return json(unwrap(await db.rpc("work_with_categories")));
    }

    const customersOf = params.get("customersOf");
    if (customersOf) {
      return json(unwrap(await db.rpc("mgr_plannable_customers", {
        p_salesperson_id: customersOf,
        p_query: params.get("query"),
      })));
    }

    return json(unwrap(await db.rpc("mgr_work_plans", {
      p_from_date: date(params.get("fromDate")),
      p_to_date: date(params.get("toDate")),
      p_statuses: list(params.get("statuses"), STATUSES),
      p_salesperson_id: params.get("salespersonId") || null,
    })));
  }

  if (req.method === "POST") {
    const body = await req.json() as Body;

    if (body.action === "assign") {
      if (!body.plan) throw new HttpError(400, "plan is required");
      const id = ruled(await db.rpc("mgr_assign_work_plan", { payload: body.plan }));
      return json({ id });
    }

    if (!body.id) throw new HttpError(400, "id is required");

    if (body.action === "decide") {
      if (typeof body.approve !== "boolean") throw new HttpError(400, "approve is required");
      const status = ruled(await db.rpc("mgr_decide_work_plan", {
        p_plan_id: body.id, p_approve: body.approve, p_reason: body.reason ?? null,
      }));
      return json({ id: body.id, status });
    }

    if (body.action === "cancel") {
      const status = ruled(await db.rpc("mgr_cancel_work_plan", {
        p_plan_id: body.id, p_reason: body.reason ?? null,
      }));
      return json({ id: body.id, status });
    }

    throw new HttpError(400, `unknown action: ${body.action}`);
  }

  throw new HttpError(405, "GET or POST only");
}));
