// =============================================================================
// GET /display-programs?customerId=…&visitId=…
//
// What this outlet is being audited on today, with this visit's own result
// attached — the legacy API_GetListDisplay, whose job was to fill the sheet the
// rep picks a programme from.
//
// visitId rather than the date. A shop can be called on twice in a day now, and
// the afternoon call should start with the programmes unscored rather than
// inheriting the morning's answers.
//
// Everything the screen needs travels here, the level's target included: the
// facing count is meaningless without the number it is measured against, and
// fetching that separately would let the two disagree.
// =============================================================================

import { handler, HttpError, json, unwrap } from "../_shared/client.ts";

interface Row {
  program_id: string;
  program_code: string;
  program_name: string;
  specification: string | null;
  from_date: string;
  to_date: string;
  registered: boolean;
  registration_status: string | null;
  level_id: string;
  level_code: string;
  level_name: string;
  required_faces: number;
  bonus_amount: number;
  audit_id: string | null;
  counted_faces: number | null;
  achieved: boolean | null;
  photo_count: number;
}

Deno.serve(handler(async (req, db) => {
  if (req.method !== "GET") throw new HttpError(405, "GET only");

  const params = new URL(req.url).searchParams;
  const customerId = params.get("customerId");
  const visitId = params.get("visitId");

  if (!customerId) throw new HttpError(400, "customerId is required");
  if (!visitId) throw new HttpError(400, "visitId is required");

  const rows = unwrap(
    await db.rpc("display_programs_for", {
      p_customer_id: customerId,
      p_visit_id: visitId,
    }),
  ) as Row[];

  return json({
    customer_id: customerId,
    visit_id: visitId,
    // Empty is a real answer: an outlet in no programme is audited on none, and
    // the step falls back to the plain photo record it used to be.
    programs: rows,
  });
}));
