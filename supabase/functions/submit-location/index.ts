// =============================================================================
// POST /submit-location   { points: [{ recorded_at, lat, lng, accuracy_m?, speed_mps?, battery_pct? }] }
//
// The tracking service's batches. submit_locations() keeps only the points
// inside the rep's declared working day and of usable accuracy, and ignores a
// point it already has, so a batch is safe to resend; the answer says how
// many were kept and dropped, and the phone discards the batch either way.
// =============================================================================

import { handler, HttpError, json, unwrap } from "../_shared/client.ts";

Deno.serve(handler(async (req, db) => {
  if (req.method !== "POST") throw new HttpError(405, "POST only");
  const body = await req.json() as { points?: unknown[] };
  if (!Array.isArray(body.points)) throw new HttpError(400, "points is required");
  if (body.points.length === 0) return json({ accepted: 0, dropped: 0 });
  return json(unwrap(await db.rpc("submit_locations", { p_points: body.points })));
}));
