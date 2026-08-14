// =============================================================================
// POST /submit-checkout
//
// Closes a visit.
//
// The update is filtered by visit id, and RLS narrows it to the caller's own
// visits on top of that. Both matter: without the filter this would close every
// visit the rep can reach, and without RLS it could close someone else's.
// =============================================================================

import { handler, HttpError, json, unwrap } from "../_shared/client.ts";

interface Body {
  visit_id?: string;
  check_out_at?: string;
  check_out_lat?: number | null;
  check_out_lng?: number | null;
  check_out_photo_path?: string | null;
}

Deno.serve(handler(async (req, db) => {
  if (req.method !== "POST") throw new HttpError(405, "POST only");

  const body = await req.json() as Body;
  if (!body.visit_id) throw new HttpError(400, "visit_id is required");
  if (!body.check_out_at) throw new HttpError(400, "check_out_at is required");

  const updated = unwrap(
    await db.from("visit")
      .update({
        check_out_at: body.check_out_at,
        check_out_lat: body.check_out_lat ?? null,
        check_out_lng: body.check_out_lng ?? null,
        check_out_photo_path: body.check_out_photo_path ?? null,
        status: "completed",
      })
      .eq("id", body.visit_id)
      .select("id"),
  ) as { id: string }[];

  // An update matching nothing is not success. It means the visit is gone or
  // belongs to another rep, and the outbox needs to hear that rather than
  // deleting the entry as delivered.
  if (updated.length === 0) {
    throw new HttpError(404, `visit ${body.visit_id} is not a visit of this salesperson`);
  }

  return json({ ok: true });
}));
