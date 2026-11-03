// =============================================================================
// eSalesManager — Giao việc / công việc của nhóm
//
// GET  /mgr-work-plans?fromDate=&toDate=&statuses=&salespersonId=  → team list
// GET  /mgr-work-plans?fromDate=&toDate=&statuses=&mine=1           → the caller's own plans
// GET  /mgr-work-plans?id=…                                         → one plan, photos signed
// GET  /mgr-work-plans?categories=1                                 → work types
// GET  /mgr-work-plans?customersOf=<salespersonId>&query=…          → object picker
// POST /mgr-work-plans
//        { action: "assign", plan: { salesperson_id, category_id, content,
//                                    note?, place?, from_at, to_at, objects: [{id}] } }
//          + repeat?: { every: "day" | "week", until: YYYY-MM-DD }   → one task per occurrence, { count }
//        { action: "decide", id, approve, reason? }
//        { action: "cancel", id, reason }
//        { action: "update", id, plan: { category_id, content, note?, place?, from_at,
//                                        to_at, objects } }  → change a task you assigned
//        { action: "plan", plan: { category_id, content, note?, place?, from_at, to_at,
//                                  objects } }                → own plan, sent for approval
//        { action: "status", id, status, reason? }    → own plan: submitted / completed / cancelled
//
// Legacy MyTaskController + TaskApproveController behind one route. Every
// database function called here checks the caller's team itself.
// =============================================================================

import { handler, HttpError, json, unwrap } from "../_shared/client.ts";
import { push } from "../_shared/push.ts";
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
  status?: string;
  plan?: Record<string, unknown>;
  /** Assign as a series: every working day or every week, until a date. */
  repeat?: { every: string; until: string };
}

/** What a manager may do to their own plan; approving it is their boss's move. */
const OWN_MOVES = ["submitted", "completed", "cancelled"];

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
      p_mine: params.get("mine") === "1",
    })));
  }

  if (req.method === "POST") {
    const body = await req.json() as Body;

    if (body.action === "assign") {
      if (!body.plan) throw new HttpError(400, "plan is required");
      // The rep is told once, however many repeats the assignment made.
      const assigned = (repeated: boolean) =>
        push([body.plan!.salesperson_id as string | undefined], "sfa", {
          title: "Kế hoạch làm việc mới",
          body: repeated ? "Quản lý vừa giao cho bạn một kế hoạch lặp lại" : "Quản lý vừa giao cho bạn một kế hoạch",
          screen: "work_plan",
        });
      if (body.repeat) {
        const count = ruled(await db.rpc("mgr_assign_work_plan_repeat", {
          payload: body.plan, p_every: body.repeat.every, p_until: date(body.repeat.until) ?? null,
        }));
        assigned(true);
        return json({ count });
      }
      const id = ruled(await db.rpc("mgr_assign_work_plan", { payload: body.plan }));
      assigned(false);
      return json({ id });
    }

    // The manager's own plan, written as the rep app writes one and sent
    // straight for approval: the manager app keeps no drafts.
    if (body.action === "plan") {
      if (!body.plan) throw new HttpError(400, "plan is required");
      const id = ruled(await db.rpc("save_work_plan", { payload: body.plan })) as string;
      const status = ruled(await db.rpc("set_work_plan_status", {
        p_plan_id: id, p_status: "submitted", p_reason: null,
      }));
      return json({ id, status });
    }

    if (!body.id) throw new HttpError(400, "id is required");

    // Change a task the caller handed down, while it is still to be done.
    if (body.action === "update") {
      if (!body.plan) throw new HttpError(400, "plan is required");
      ruled(await db.rpc("mgr_update_work_plan", { p_plan_id: body.id, payload: body.plan }));
      return json({ id: body.id });
    }

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

    if (body.action === "status") {
      if (!body.status || !OWN_MOVES.includes(body.status)) {
        throw new HttpError(400, `status must be one of ${OWN_MOVES.join(", ")}`);
      }
      const status = ruled(await db.rpc("set_work_plan_status", {
        p_plan_id: body.id, p_status: body.status, p_reason: body.reason ?? null,
      }));
      return json({ id: body.id, status });
    }

    throw new HttpError(400, `unknown action: ${body.action}`);
  }

  throw new HttpError(405, "GET or POST only");
}));
