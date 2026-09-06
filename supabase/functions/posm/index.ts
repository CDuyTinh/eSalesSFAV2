// =============================================================================
// GET /posm?customerId=…&visitId=…
//
// Both halves of the legacy's POSM screen in one call — API_GetPOSM_IsUsing's
// type U and type R, which over there were two round trips to the same proc.
//
// They travel together because the rep reads them together: what is in the shop
// and what the shop is still waiting for are one question asked twice, and a
// screen with two tabs that load separately shows one of them stale.
//
// visitId keys the checks rather than the date. A shop can be called on twice in
// a day, and the second call should start with its assets unchecked rather than
// inheriting the morning's answers.
// =============================================================================

import { handler, HttpError, json, unwrap } from "../_shared/client.ts";

interface PlacedRow {
  program_id: string;
  program_code: string;
  program_name: string;
  posm_item_id: string;
  item_code: string;
  item_name: string;
  unit_name: string;
  image_url: string | null;
  placed_qty: number;
  placed_at: string;
  check_id: string | null;
  counted_qty: number | null;
  condition: string | null;
  remark: string | null;
  suggestion: string | null;
  photo_count: number;
}

interface RegistrationRow {
  program_id: string;
  program_code: string;
  program_name: string;
  from_date: string;
  to_date: string;
  posm_item_id: string;
  item_code: string;
  item_name: string;
  unit_name: string;
  image_url: string | null;
  regis_qty: number;
  approved_qty: number;
  delivered_qty: number;
  status: string;
  registered_at: string;
}

Deno.serve(handler(async (req, db) => {
  if (req.method !== "GET") throw new HttpError(405, "GET only");

  const params = new URL(req.url).searchParams;
  const customerId = params.get("customerId");
  const visitId = params.get("visitId");

  if (!customerId) throw new HttpError(400, "customerId is required");
  if (!visitId) throw new HttpError(400, "visitId is required");

  const [placed, registrations] = await Promise.all([
    db.rpc("posm_at_customer", {
      p_customer_id: customerId,
      p_visit_id: visitId,
    }),
    db.rpc("posm_registrations_for", { p_customer_id: customerId }),
  ]);

  return json({
    customer_id: customerId,
    visit_id: visitId,
    // Empty is a real answer, not a failure: plenty of outlets hold none of the
    // company's furniture, and the step has to say so rather than spin.
    placed: unwrap(placed) as PlacedRow[],
    registrations: unwrap(registrations) as RegistrationRow[],
  });
}));
