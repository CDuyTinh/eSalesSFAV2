// =============================================================================
// POST /submit-posm-check
//
// Forwards to `submit_posm_check`, or — when the body names no asset — to
// `complete_empty_posm_step`.
//
// The empty case is a real one and needs an answer of its own. A shop holding
// none of the company's furniture has nothing to count, and a step that can
// never be completed would sit red on the list forever with no way for the rep
// to say "I looked, there is none". That is what the legacy's own
// "posm is empty" alert amounts to once it has been read.
//
// Thin otherwise, like the other write functions: the check, its photo rows and
// the posm_status step are one transaction, and the guards that make the check
// trustworthy — the step's photo_min, the storage paths existing, the asset
// actually being at this outlet — belong next to the data they check.
//
// The photos are already in storage by the time this is called. The client
// uploads first and passes the object names, because the function would
// otherwise refuse paths that are not there yet.
// =============================================================================

import { handler, HttpError, json, unwrap } from "../_shared/client.ts";

Deno.serve(handler(async (req, db) => {
  if (req.method !== "POST") throw new HttpError(405, "POST only");

  const body = await req.json();

  if (!body?.posm_item_id) {
    const visitId = body?.visit_id;
    if (!visitId) throw new HttpError(400, "visit_id is required");

    const completed = unwrap(
      await db.rpc("complete_empty_posm_step", { p_visit_id: visitId }),
    );

    // False means the outlet does have POSM after all and the client was working
    // from a stale list. Refusing beats reporting success on a step still owed —
    // the rep would leave believing assets had been accounted for.
    if (!completed) {
      throw new HttpError(409, "this outlet does have POSM to check");
    }

    return json({ posm_check_id: null, completed_empty: true });
  }

  const checkId = unwrap(await db.rpc("submit_posm_check", { p_check: body }));

  return json({ posm_check_id: checkId });
}));
