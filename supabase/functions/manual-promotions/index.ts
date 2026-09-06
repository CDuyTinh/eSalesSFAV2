// =============================================================================
// GET /manual-promotions?customerId=…
//
// The catalogue of discounts a rep may apply by hand at this outlet today — the
// legacy API_GetManualPromo.
//
// Its own endpoint rather than a field on /promotions, because it does not
// depend on the basket. The order screen fetches it once when it opens; the
// automatic preview it refreshes on every keystroke has no reason to carry it.
// =============================================================================

import { handler, HttpError, json, unwrap } from "../_shared/client.ts";

interface Row {
  promotion_id: string;
  code: string;
  name: string;
  promo_type: string;
  value: number;
  allow_edit: boolean;
  from_date: string;
  to_date: string;
  items: unknown[];
}

Deno.serve(handler(async (req, db) => {
  if (req.method !== "GET") throw new HttpError(405, "GET only");

  const customerId = new URL(req.url).searchParams.get("customerId");
  if (!customerId) throw new HttpError(400, "customerId is required");

  const rows = unwrap(
    await db.rpc("manual_promotions_for", { p_customer_id: customerId }),
  ) as Row[];

  return json({
    // Empty is ordinary: a market that runs no manual discounts is a market
    // where the rep has nothing to give away, not an error.
    promotions: rows,
  });
}));
