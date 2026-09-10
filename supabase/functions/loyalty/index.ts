// =============================================================================
// GET /loyalty?customerId=…
//
// Both halves of the loyalty tab in one call: what this outlet is accumulating
// towards, and what it could still be signed up for. API_GetTradeProgramByCust
// with TradeType 'A', plus the progress the legacy does not compute.
//
// They travel together because the rep reads them together — "where are we, and
// what else could we put them in" is one question asked twice, and a screen with
// two sections that load separately shows one of them stale.
// =============================================================================

import { handler, HttpError, json, unwrap } from "../_shared/client.ts";

interface JoinedRow {
  program_id: string;
  program_code: string;
  program_name: string;
  specification: string | null;
  counts_by: "amount" | "quantity";
  from_date: string;
  to_date: string;
  level_id: string;
  level_code: string;
  level_name: string;
  target_from: number;
  target_to: number | null;
  reward_basis_points: number;
  status: string;
  registered_at: string;
  achieved: number;
  remaining: number;
}

interface OpenRow {
  program_id: string;
  program_code: string;
  program_name: string;
  specification: string | null;
  counts_by: "amount" | "quantity";
  from_date: string;
  to_date: string;
  regis_from_date: string | null;
  regis_to_date: string | null;
  levels: Array<{
    level_id: string;
    level_code: string;
    level_name: string;
    target_from: number;
    target_to: number | null;
    reward_basis_points: number;
    slots_left: number | null;
  }>;
}

Deno.serve(handler(async (req, db) => {
  if (req.method !== "GET") throw new HttpError(405, "GET only");

  const customerId = new URL(req.url).searchParams.get("customerId");
  if (!customerId) throw new HttpError(400, "customerId is required");

  const [joined, open] = await Promise.all([
    db.rpc("loyalty_for", { p_customer_id: customerId }),
    db.rpc("loyalty_open_for", { p_customer_id: customerId }),
  ]);

  return json({
    customer_id: customerId,
    // Empty on either side is a real answer, not a failure.
    joined: unwrap(joined) as JoinedRow[],
    open: unwrap(open) as OpenRow[],
  });
}));
