// =============================================================================
// POST /submit-display-audit
//
// Forwards to the `submit_display_audit` database function.
//
// Thin for the same reason as the other write functions: the header, the photo rows
// and the display_remark step are one transaction, and the two guards that make the
// audit trustworthy — the step's own photo_min, and every storage path being checked
// to exist — belong next to the data they check. Storage and the database are
// separate systems, and that existence check is what makes "the row exists" mean
// "the photo exists".
//
// The photos are already in storage by the time this is called. The client uploads
// first and passes the object names, because the function would otherwise refuse
// paths that are not there yet.
// =============================================================================

import { handler, HttpError, json, unwrap } from "../_shared/client.ts";

Deno.serve(handler(async (req, db) => {
  if (req.method !== "POST") throw new HttpError(405, "POST only");

  const audit = await req.json();
  const auditId = unwrap(await db.rpc("submit_display_audit", { p_audit: audit }));

  return json({ display_audit_id: auditId });
}));
