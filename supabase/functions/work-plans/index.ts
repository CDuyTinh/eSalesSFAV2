// =============================================================================
// GET /work-plans?fromDate=…&toDate=…&statuses=…&categoryIds=…&assignedToMe=…
// GET /work-plans?planId=…
//
// The whole of MY_TASK and WORKING_PLAN, which over there are two controllers
// and five endpoints. With a planId it returns that one plan in full; without,
// the list, its per-status counts, the month calendar and the work-type
// catalogue.
//
// They travel together because the screen shows them together: a calendar cell
// painted from one fetch and a list drawn from another disagree the moment a
// plan changes, and the catalogue is seven rows the add form should never wait
// for.
// =============================================================================

import { handler, HttpError, json, unwrap } from "../_shared/client.ts";

const STATUSES = [
  "draft",
  "submitted",
  "approved",
  "rejected",
  "completed",
  "cancelled",
] as const;

function date(value: string | null, name: string): string | undefined {
  if (!value) return undefined;
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value)) {
    throw new HttpError(400, `${name} is not a date`);
  }
  return value;
}

/** A comma-separated list, or undefined when the caller did not narrow it. */
function list(value: string | null): string[] | undefined {
  if (!value) return undefined;
  const parts = value.split(",").map((s) => s.trim()).filter(Boolean);
  return parts.length > 0 ? parts : undefined;
}

Deno.serve(handler(async (req, db) => {
  if (req.method !== "GET") throw new HttpError(405, "GET only");

  const params = new URL(req.url).searchParams;

  // The object picker asks the same endpoint: it is the same feature, and a
  // route of its own for four columns would be a route to keep in step.
  const pick = params.get("pickCustomers");
  if (pick !== null) {
    return json({
      customers: unwrap(
        await db.rpc("plannable_customers", { p_query: pick || null }),
      ),
    });
  }

  const planId = params.get("planId");
  if (planId) {
    const detail = unwrap(await db.rpc("work_plan_detail", { p_plan_id: planId }));
    // Null means it is not this rep's plan. Not found and not yours are the
    // same answer on purpose.
    if (!detail) throw new HttpError(404, "plan not found");
    return json({ plan: detail });
  }

  const statuses = list(params.get("statuses"));
  statuses?.forEach((s) => {
    if (!STATUSES.includes(s as typeof STATUSES[number])) {
      throw new HttpError(400, `${s} is not a status`);
    }
  });

  const fromDate = date(params.get("fromDate"), "fromDate");
  const toDate = date(params.get("toDate"), "toDate");

  const assigned = params.get("assignedToMe");

  const [listed, calendar, categories] = await Promise.all([
    db.rpc("work_plans_for", {
      p_from_date: fromDate,
      p_to_date: toDate,
      p_statuses: statuses,
      p_category_ids: list(params.get("categoryIds")),
      p_assigned_to_me: assigned === null ? undefined : assigned === "true",
      p_limit: Number(params.get("limit") ?? 50),
      p_offset: Number(params.get("offset") ?? 0),
    }),
    db.rpc("work_plan_calendar", {
      p_from_date: fromDate,
      p_to_date: toDate,
    }),
    db.rpc("work_with_categories"),
  ]);

  const page = unwrap(listed) as {
    total: number;
    counts: Record<string, number>;
    items: unknown[];
  };

  return json({
    total: page.total,
    counts: page.counts,
    items: page.items,
    calendar: unwrap(calendar),
    categories: unwrap(categories),
  });
}));
