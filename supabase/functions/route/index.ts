// =============================================================================
// GET /route?date=YYYY-MM-DD
//
// Today's stops with each visit's status already attached.
//
// Replaces two client round trips and the merge between them. The client used to
// fetch route_customer for the weekday, fetch visit for the date, then join them
// by customer id — three things that could disagree. Here the join happens once,
// next to the data.
//
// RLS still does the scoping: neither query carries a branch or salesperson
// filter, exactly as before. That is the whole reason scoping lives in the
// database rather than in whichever layer happens to be asking.
// =============================================================================

import { handler, HttpError, json, unwrap } from "../_shared/client.ts";

const ROUTE_COLUMNS =
  "visit_order,customer:customer_id(id,code,name,address,phone,lat,lng," +
  "avatar_url,checkin_radius_m,class_id)";

const VISIT_COLUMNS = "id,customer_id,status,check_in_at,check_out_at";

interface Customer {
  id: string;
}

interface RouteRow {
  visit_order: number;
  customer: Customer | null;
}

interface VisitRow {
  id: string;
  customer_id: string;
  status: string;
  check_in_at: string | null;
  check_out_at: string | null;
}

Deno.serve(handler(async (req, db) => {
  const date = new URL(req.url).searchParams.get("date");
  if (!date) throw new HttpError(400, "date is required (YYYY-MM-DD)");

  // ISO weekday: Monday = 1. Built from the date rather than taken from the
  // caller so the weekday can never disagree with the date it belongs to.
  const parsed = new Date(`${date}T00:00:00Z`);
  if (Number.isNaN(parsed.getTime())) {
    throw new HttpError(400, `date is not a valid date: ${date}`);
  }
  const isoWeekday = parsed.getUTCDay() === 0 ? 7 : parsed.getUTCDay();

  const [stops, visits] = await Promise.all([
    db
      .from("route_customer")
      .select(ROUTE_COLUMNS)
      .eq("is_active", true)
      .contains("visit_weekdays", [isoWeekday])
      .order("visit_order", { ascending: true })
      .then((r) => unwrap(r) as RouteRow[]),
    db
      .from("visit")
      .select(VISIT_COLUMNS)
      .eq("visit_date", date)
      .then((r) => unwrap(r) as VisitRow[]),
  ]);

  const byCustomer = new Map(visits.map((v) => [v.customer_id, v]));

  return json({
    date,
    stops: stops
      // A route row whose customer is not readable — another branch's, say —
      // arrives with a null embed. Dropping it beats emitting a stop the rep
      // cannot open.
      .filter((row) => row.customer !== null)
      .map((row) => {
        const visit = byCustomer.get(row.customer!.id) ?? null;
        return {
          visit_order: row.visit_order,
          customer: row.customer,
          visit_id: visit?.id ?? null,
          status: visit?.status ?? "planned",
          check_in_at: visit?.check_in_at ?? null,
          check_out_at: visit?.check_out_at ?? null,
        };
      }),
  });
}));
