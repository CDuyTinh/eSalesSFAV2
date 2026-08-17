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

// channel_id and shop_type_id come along because the must-stock lists are scoped
// by them: the stock screen resolves which SKUs are required from the outlet's own
// segment, and re-fetching the customer to learn it would be a second round trip
// inside a screen that already has one.
const CUSTOMER_COLUMNS =
  "id,code,name,address,phone,lat,lng," +
  "avatar_url,checkin_radius_m,class_id,channel_id,shop_type_id";

const ROUTE_COLUMNS = `visit_order,customer:customer_id(${CUSTOMER_COLUMNS})`;

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

  // Reconcile before reading, so the route the rep is shown is already true.
  //
  // A visit checked into on an earlier day and never checked out of used to stay
  // 'in_progress' for ever: this endpoint only returns the requested day's stops,
  // so the row became unreachable from the app the moment the date rolled over
  // while still counting as an open visit everywhere else. Start of day is the
  // natural moment to settle that, and it is the one moment the app is certain to
  // ask for. The date is forwarded because the rep's "yesterday" is a question
  // about their timezone, not this server's.
  //
  // Failing here must not cost the rep their route, so the outcome is not checked:
  // the reconciliation is housekeeping, and it will get another chance on the next
  // load. RLS confines it to the caller's own visits either way.
  await db.rpc("close_abandoned_visits", { p_before: date });

  // Needed only to scope the registered outlets below. RLS answers every other
  // query here without being told who is asking.
  const me = (unwrap(
    await db.from("salesperson").select("id"),
  ) as { id: string }[])[0];

  if (!me) throw new HttpError(403, "no salesperson is linked to this account");
  const meId = me.id;

  const [stops, visits, registered] = await Promise.all([
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
    // Outlets this rep registered in the field and head office has not ruled on.
    //
    // They belong on the route or the registration is inert: the rep met the
    // shop, and everything they might do there — check in, take an order, count
    // the shelves — hangs off a stop. They stay until the decision comes: once
    // approved, head office puts the outlet in an MCP route and it arrives above
    // through the normal join; once rejected, it stops appearing at all.
    //
    // Filtered to the caller explicitly. RLS scopes `customer` to the branch,
    // which is right for reading and wrong here: an unapproved outlet is the
    // errand of the rep who walked into it, and a colleague's provisional shop
    // on this list would be work nobody assigned.
    db
      .from("customer")
      .select(CUSTOMER_COLUMNS)
      .eq("approval_status", "pending")
      .eq("is_active", true)
      .eq("created_by_salesperson_id", meId)
      .order("created_at", { ascending: true })
      .then((r) => unwrap(r) as Customer[]),
  ]);

  const byCustomer = new Map(visits.map((v) => [v.customer_id, v]));

  const asStop = (customer: Customer, order: number, unplanned: boolean) => {
    const visit = byCustomer.get(customer.id) ?? null;
    return {
      visit_order: order,
      customer,
      unplanned,
      visit_id: visit?.id ?? null,
      status: visit?.status ?? "planned",
      check_in_at: visit?.check_in_at ?? null,
      check_out_at: visit?.check_out_at ?? null,
    };
  };

  const planned = stops
    // A route row whose customer is not readable — another branch's, say —
    // arrives with a null embed. Dropping it beats emitting a stop the rep
    // cannot open.
    .filter((row) => row.customer !== null)
    .map((row) => asStop(row.customer!, row.visit_order, false));

  // A registered outlet that has since been added to today's MCP route is
  // already above; it must not appear twice.
  const plannedIds = new Set(planned.map((s) => s.customer.id));
  const extra = registered
    .filter((c) => !plannedIds.has(c.id))
    .map((c, i) => asStop(c, planned.length + i + 1, true));

  return json({ date, stops: [...planned, ...extra] });
}));
