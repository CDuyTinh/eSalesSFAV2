// =============================================================================
// GET /work-day?date=YYYY-MM-DD
//
// Where the rep's selling day stands: the depot they clock in at, whether they
// have clocked in, whether they have clocked out, and how many visits are still
// open.
//
// One call rather than three. The screen that asks this is the one blocking the
// rep from starting work, so it should not be able to render a state assembled
// from reads taken at different instants — clocked in according to one, no
// branch according to another.
//
// `open_visits` is here rather than left to the client because it decides
// whether the day can be closed at all, and the rep deserves to be told that
// before they stand in the depot pressing a button that will be refused.
// =============================================================================

import { handler, HttpError, json, unwrap } from "../_shared/client.ts";

interface Me {
  id: string;
  branch: {
    id: string;
    code: string;
    name: string;
    address: string | null;
    lat: number | null;
    lng: number | null;
  } | null;
}

interface Punch {
  type: "check_in" | "check_out";
  happened_at: string;
}

Deno.serve(handler(async (req, db) => {
  if (req.method !== "GET") throw new HttpError(405, "GET only");

  const date = new URL(req.url).searchParams.get("date");
  if (!date) throw new HttpError(400, "date is required");

  const me = (unwrap(
    await db
      .from("salesperson")
      .select("id, branch:branch_id (id, code, name, address, lat, lng)"),
  ) as Me[])[0];

  if (!me) throw new HttpError(403, "no salesperson is linked to this account");
  if (!me.branch) throw new HttpError(409, "this account has no branch to clock in at");

  const punches = unwrap(
    await db
      .from("timekeeping")
      .select("type, happened_at")
      .eq("work_date", date),
  ) as Punch[];

  // RLS already scopes both of these to the caller, so neither filters on the
  // rep: adding a salesperson_id here would be a second, weaker copy of the rule
  // that is already enforced underneath.
  const openVisits = unwrap(
    await db
      .from("visit")
      .select("id")
      .eq("visit_date", date)
      .eq("status", "in_progress"),
  ) as { id: string }[];

  return json({
    work_date: date,
    branch: me.branch,
    check_in_at: punches.find((p) => p.type === "check_in")?.happened_at ?? null,
    check_out_at: punches.find((p) => p.type === "check_out")?.happened_at ?? null,
    open_visits: openVisits.length,
  });
}));
