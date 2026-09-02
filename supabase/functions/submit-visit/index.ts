// =============================================================================
// POST /submit-visit
//
// Records a check-in.
//
// The rep's identity is derived here, not accepted from the body. The client used
// to send salesperson_id and branch_id, and RLS's insert check refused a mismatch
// — so it was safe, but it was safe by being caught rather than by being
// impossible. Deriving them removes the question.
//
// `status` is likewise fixed rather than taken: a check-in produces exactly one
// status, and letting the caller name it invites a visit that is checked in and
// somehow already completed.
// =============================================================================

import { handler, HttpError, json, unwrap } from "../_shared/client.ts";

interface Body {
  customer_id?: string;
  visit_date?: string;
  check_in_at?: string;
  check_in_lat?: number;
  check_in_lng?: number;
  check_in_accuracy_m?: number | null;
  check_in_distance_m?: number | null;
  check_in_photo_path?: string | null;
  check_in_reason_id?: string | null;
  note?: string | null;
}

Deno.serve(handler(async (req, db) => {
  if (req.method !== "POST") throw new HttpError(405, "POST only");

  const body = await req.json() as Body;
  if (!body.customer_id) throw new HttpError(400, "customer_id is required");
  if (!body.check_in_at) throw new HttpError(400, "check_in_at is required");

  const me = (unwrap(
    await db.from("salesperson").select("id,branch_id"),
  ) as { id: string; branch_id: string }[])[0];

  if (!me) throw new HttpError(403, "no salesperson is linked to this account");

  const visitDate = body.visit_date ?? body.check_in_at.slice(0, 10);

  // One open visit at a time, as the legacy app enforced on its check-in button.
  // A unique index makes this impossible anyway; this exists so the rep is told
  // which shop is holding them up instead of receiving a constraint violation.
  //
  // Not a transaction with the insert below, so two check-ins racing could both
  // pass this check. That is exactly what the index is for — one of them then
  // fails on it, which is the correct outcome and not one worth a lock to reach
  // more gracefully.
  const open = unwrap(
    await db.from("visit")
      .select("id,customer:customer_id(name)")
      .eq("visit_date", visitDate)
      .eq("status", "in_progress"),
  ) as { id: string; customer: { name: string } | null }[];

  if (open.length > 0) {
    const name = open[0].customer?.name ?? "một khách hàng khác";
    throw new HttpError(409, `Đang viếng thăm ${name}. Check-out trước đã.`);
  }

  unwrap(
    await db.from("visit").insert({
      customer_id: body.customer_id,
      salesperson_id: me.id,
      branch_id: me.branch_id,
      // Defaults to the check-in instant's date so a queued check-in delivered
      // after midnight still belongs to the day the rep was in the shop.
      visit_date: visitDate,
      status: "in_progress",
      check_in_at: body.check_in_at,
      check_in_lat: body.check_in_lat ?? null,
      check_in_lng: body.check_in_lng ?? null,
      check_in_accuracy_m: body.check_in_accuracy_m ?? null,
      check_in_distance_m: body.check_in_distance_m ?? null,
      check_in_photo_path: body.check_in_photo_path ?? null,
      check_in_reason_id: body.check_in_reason_id ?? null,
      note: body.note ?? null,
    }).select("id"),
  );

  return json({ ok: true });
}));
