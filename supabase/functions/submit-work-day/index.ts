// =============================================================================
// POST /submit-work-day
//
// Opens or closes the rep's selling day.
//
// The rep and their branch are derived here rather than accepted, for the reason
// submit-visit gives: RLS would catch a mismatched id, but being caught is a
// weaker property than being impossible.
//
// Two guards sit in front of closing the day, and they are different in kind:
//
//   - clocking out without having clocked in is refused here, because the pair
//     is an ordering and the database cannot express one;
//   - clocking out with a visit still open is refused here too, because ending
//     the day would strand that visit — it would be closed hours later by the
//     abandoned-visit sweep and recorded as a stop the rep walked away from.
//
// Clocking in twice is not guarded here at all. The unique index on
// (salesperson_id, work_date, type) refuses it, and a check in this function
// would only be a race the index would win anyway.
// =============================================================================

import { handler, HttpError, json, unwrap } from "../_shared/client.ts";

interface Body {
  type?: "check_in" | "check_out";
  work_date?: string;
  happened_at?: string;
  lat?: number | null;
  lng?: number | null;
  accuracy_m?: number | null;
  distance_m?: number | null;
  reason_id?: string | null;
}

Deno.serve(handler(async (req, db) => {
  if (req.method !== "POST") throw new HttpError(405, "POST only");

  const body = await req.json() as Body;
  if (body.type !== "check_in" && body.type !== "check_out") {
    throw new HttpError(400, "type must be check_in or check_out");
  }
  if (!body.happened_at) throw new HttpError(400, "happened_at is required");

  // Same rule as a visit: the day the rep was standing there, not the day the
  // request happened to arrive.
  const workDate = body.work_date ?? body.happened_at.slice(0, 10);

  const me = (unwrap(
    await db.from("salesperson").select("id,branch_id"),
  ) as { id: string; branch_id: string }[])[0];

  if (!me) throw new HttpError(403, "no salesperson is linked to this account");

  if (body.type === "check_out") {
    const punches = unwrap(
      await db.from("timekeeping").select("type").eq("work_date", workDate),
    ) as { type: string }[];

    // The three refusals below are written in Vietnamese, unlike the contract
    // errors above them. They are the only messages here a rep will ever read —
    // the rest mean the client sent something malformed, which is a developer's
    // problem and never the rep's.
    if (!punches.some((p) => p.type === "check_in")) {
      throw new HttpError(409, "chưa mở ngày bán hàng hôm nay");
    }

    const openVisits = unwrap(
      await db
        .from("visit")
        .select("id")
        .eq("visit_date", workDate)
        .eq("status", "in_progress"),
    ) as { id: string }[];

    if (openVisits.length > 0) {
      throw new HttpError(
        409,
        `còn ${openVisits.length} cuộc viếng thăm chưa check-out`,
      );
    }
  }

  const inserted = await db.from("timekeeping").insert({
    salesperson_id: me.id,
    branch_id: me.branch_id,
    type: body.type,
    work_date: workDate,
    happened_at: body.happened_at,
    lat: body.lat ?? null,
    lng: body.lng ?? null,
    accuracy_m: body.accuracy_m ?? null,
    distance_m: body.distance_m ?? null,
    reason_id: body.reason_id ?? null,
  }).select("id");

  // The index is what refuses a second punch; this only dresses its answer.
  // "duplicate key value violates unique constraint" is true and useless to a rep
  // standing in the depot wondering whether they are clocked in.
  if (inserted.error?.code === "23505") {
    throw new HttpError(
      409,
      body.type === "check_in"
        ? "ngày bán hàng hôm nay đã được mở rồi"
        : "ngày bán hàng hôm nay đã được đóng rồi",
    );
  }
  unwrap(inserted);

  return json({ ok: true });
}));
