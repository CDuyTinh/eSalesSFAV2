// =============================================================================
// GET /display-open?customerId=…
//
// Display programmes this outlet is not in yet and may still be signed up for
// today — API_GetTradeProgramByCust with TradeType 'D', whose whole job is to
// fill the sheet a rep signs an outlet up from.
//
// Each level carries the slots this rep has left at it. A level with none is
// still returned: "hết suất" is information the rep needs, and a level that
// silently vanishes is a phone call to head office asking where it went.
// =============================================================================

import { handler, HttpError, json, unwrap } from "../_shared/client.ts";

interface OpenProgramRow {
  program_id: string;
  program_code: string;
  program_name: string;
  specification: string | null;
  from_date: string;
  to_date: string;
  regis_from_date: string | null;
  regis_to_date: string | null;
  levels: Array<{
    level_id: string;
    level_code: string;
    level_name: string;
    required_faces: number;
    bonus_amount: number;
    /** Null when head office set this rep no ceiling at all. */
    slots_left: number | null;
  }>;
}

Deno.serve(handler(async (req, db) => {
  if (req.method !== "GET") throw new HttpError(405, "GET only");

  const customerId = new URL(req.url).searchParams.get("customerId");
  if (!customerId) throw new HttpError(400, "customerId is required");

  const programs = unwrap(
    await db.rpc("display_programs_open_for", { p_customer_id: customerId }),
  ) as OpenProgramRow[];

  return json({
    customer_id: customerId,
    // Empty is a real answer: an outlet already in everything its branch runs
    // has nothing left to join, and the screen says so rather than spinning.
    programs,
  });
}));
