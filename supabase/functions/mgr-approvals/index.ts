// =============================================================================
// eSalesManager — Phê duyệt
//
// GET  /mgr-approvals                                  → counts per kind (pending)
// GET  /mgr-approvals?kind=leave&statuses=pending,…    → leave list
// GET  /mgr-approvals?kind=customer&statuses=…&query=… → field-registered outlets
// GET  /mgr-approvals?kind=customer&id=…               → one outlet, photos signed
// GET  /mgr-approvals?kind=trade&types=display,…&statuses=…
//                                                      → programme sign-ups
// POST /mgr-approvals
//        { kind: "leave"|"customer"|"display"|"loyalty"|"posm",
//          id, approve, note?, routeId?, approvedQty? }
//
// One route for the whole approval tab, the way the legacy's approval menu read
// several controllers but presented one screen. Every database function called
// here checks team membership itself; this layer only shapes parameters.
// =============================================================================

import { handler, HttpError, json, unwrap } from "../_shared/client.ts";
import type { SupabaseClient } from "jsr:@supabase/supabase-js@2";

const STATUSES = ["pending", "approved", "rejected"];
const TRADE_KINDS = ["display", "loyalty", "posm"];

function list(value: string | null, allowed: string[]): string[] | null {
  if (!value) return null;
  const parts = value.split(",").map((s) => s.trim()).filter((s) => allowed.includes(s));
  return parts.length > 0 ? parts : null;
}

/** The bucket is private; the screen needs URLs it can load. An hour is plenty. */
async function signPhotos(db: SupabaseClient, detail: Record<string, unknown> | null) {
  const photos = detail?.photos as Array<Record<string, unknown>> | undefined;
  if (!detail || !photos?.length) return detail;
  const signed = await Promise.all(photos.map(async (photo) => {
    const { data } = await db.storage.from("visit-photos")
      .createSignedUrl(photo.storage_path as string, 60 * 60);
    return { ...photo, url: data?.signedUrl ?? null };
  }));
  return { ...detail, photos: signed };
}

interface DecideBody {
  kind?: string;
  id?: string;
  approve?: boolean;
  note?: string;
  routeId?: string;
  approvedQty?: number;
}

/** A refusal raised in plpgsql is a business answer, not a server fault. */
function decided(result: { data: unknown; error: { code?: string; message: string } | null }) {
  if (result.error?.code === "P0001") throw new HttpError(409, result.error.message);
  return unwrap(result);
}

Deno.serve(handler(async (req, db) => {
  if (req.method === "GET") {
    const params = new URL(req.url).searchParams;
    const kind = params.get("kind");
    const statuses = list(params.get("statuses"), STATUSES);

    if (!kind) return json(unwrap(await db.rpc("mgr_approval_counts")));

    if (kind === "leave") {
      return json(unwrap(await db.rpc("mgr_leave_list", { p_statuses: statuses })));
    }

    if (kind === "customer") {
      const id = params.get("id");
      if (id) {
        const detail = unwrap(await db.rpc("mgr_customer_detail", { p_id: id })) as
          | Record<string, unknown>
          | null;
        if (!detail) throw new HttpError(404, "không tìm thấy khách hàng trong nhóm của bạn");
        return json(await signPhotos(db, detail));
      }
      return json(unwrap(await db.rpc("mgr_customer_list", {
        p_statuses: statuses,
        p_query: params.get("query"),
      })));
    }

    if (kind === "trade") {
      return json(unwrap(await db.rpc("mgr_trade_list", {
        p_kinds: list(params.get("types"), TRADE_KINDS),
        p_statuses: statuses,
      })));
    }

    throw new HttpError(400, `unknown kind: ${kind}`);
  }

  if (req.method === "POST") {
    const body = await req.json() as DecideBody;
    if (!body.id) throw new HttpError(400, "id is required");
    if (typeof body.approve !== "boolean") throw new HttpError(400, "approve is required");

    if (body.kind === "leave") {
      return json(decided(await db.rpc("mgr_decide_leave", {
        p_id: body.id, p_approve: body.approve, p_note: body.note ?? null,
      })));
    }

    if (body.kind === "customer") {
      return json(decided(await db.rpc("mgr_decide_customer", {
        p_id: body.id, p_approve: body.approve, p_note: body.note ?? null,
        p_route_id: body.routeId ?? null,
      })));
    }

    if (body.kind && TRADE_KINDS.includes(body.kind)) {
      return json(decided(await db.rpc("mgr_decide_trade", {
        p_kind: body.kind, p_id: body.id, p_approve: body.approve,
        p_note: body.note ?? null, p_approved_qty: body.approvedQty ?? null,
      })));
    }

    throw new HttpError(400, `unknown kind: ${body.kind}`);
  }

  throw new HttpError(405, "GET or POST only");
}));
