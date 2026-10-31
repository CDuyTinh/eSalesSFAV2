// =============================================================================
// eSalesManager — Báo cáo
//
// GET /mgr-reports?report=activities&date=YYYY-MM-DD[&salespersonId=…]
// GET /mgr-reports?report=sales&month=YYYY-MM-01[&salespersonId=…]
// GET /mgr-reports?report=orders&fromDate=&toDate=[&statuses=new,confirmed][&salespersonId=…]
// GET /mgr-reports?report=sku&fromDate=&toDate=[&query=][&salespersonId=…]
// GET /mgr-reports?report=trade&fromDate=&toDate=[&salespersonId=…]
// GET /mgr-reports?report=work&fromDate=&toDate=[&salespersonId=…]
// GET /mgr-reports?report=training&fromDate=&toDate=[&salespersonId=…]
// GET /mgr-reports?report=tracking&date=YYYY-MM-DD[&salespersonId=…][&supervisorId=…]
//
// The six SFA reports, asked of the team or of one rep in it. They run through
// the same report_*_of() cores the rep's own /reports uses, so a figure here is
// the figure the rep sees. Without salespersonId the whole team is reported.
// =============================================================================

import { handler, HttpError, json, unwrap } from "../_shared/client.ts";

const ORDER_STATUSES = ["new", "confirmed", "cancelled"];

function date(value: string | null, name: string): string {
  if (!value || !/^\d{4}-\d{2}-\d{2}$/.test(value)) {
    throw new HttpError(400, `${name} is required (YYYY-MM-DD)`);
  }
  return value;
}

/** A refusal raised in plpgsql (rep outside the team) is a 403, not a 500. */
function ruled<T>(result: { data: T | null; error: { code?: string; message: string } | null }) {
  if (result.error?.code === "P0001") throw new HttpError(403, result.error.message);
  return unwrap(result);
}

Deno.serve(handler(async (req, db) => {
  if (req.method !== "GET") throw new HttpError(405, "GET only");
  const params = new URL(req.url).searchParams;

  const one = params.get("salespersonId");
  const sps: string[] = one
    ? [one]
    // The team's reps, optionally those under one supervisor (AM/RM filter):
    // managers sell nothing, so they have no place in a sales report.
    : (ruled(await db.rpc("team_reps", { p_supervisor: params.get("supervisorId") || null })) as string[]);
  if (sps.length === 0) return json({ empty: true });

  switch (params.get("report")) {
    case "activities":
      return json(ruled(await db.rpc("report_activities_of", {
        p_sps: sps, p_date: date(params.get("date"), "date"),
      })));

    case "sales":
      return json(ruled(await db.rpc("report_sales_of", {
        p_sps: sps, p_month: date(params.get("month"), "month"),
      })));

    case "orders": {
      const statuses = (params.get("statuses") ?? "").split(",")
        .map((s) => s.trim()).filter((s) => ORDER_STATUSES.includes(s));
      return json(ruled(await db.rpc("report_orders_of", {
        p_sps: sps,
        p_from_date: date(params.get("fromDate"), "fromDate"),
        p_to_date: date(params.get("toDate"), "toDate"),
        p_statuses: statuses.length ? statuses : null,
        p_limit: 200,
      })));
    }

    case "sku":
      return json({
        items: ruled(await db.rpc("report_sku_of", {
          p_sps: sps,
          p_from_date: date(params.get("fromDate"), "fromDate"),
          p_to_date: date(params.get("toDate"), "toDate"),
          p_query: params.get("query"),
        })),
      });

    case "trade":
      return json(ruled(await db.rpc("report_trade_of", {
        p_sps: sps,
        p_from_date: date(params.get("fromDate"), "fromDate"),
        p_to_date: date(params.get("toDate"), "toDate"),
      })));

    case "work":
      return json(ruled(await db.rpc("report_work_result_of", {
        p_sps: sps,
        p_from_date: date(params.get("fromDate"), "fromDate"),
        p_to_date: date(params.get("toDate"), "toDate"),
      })));

    case "tracking":
      return json(ruled(await db.rpc("report_tracking_of", {
        p_sps: sps, p_date: date(params.get("date"), "date"),
      })));

    // The caller's own EDAC coaching plans, not the reps' work: the team list
    // above only narrows it to one rep when salespersonId is given.
    case "training":
      return json(ruled(await db.rpc("mgr_report_training", {
        p_from: date(params.get("fromDate"), "fromDate"),
        p_to: date(params.get("toDate"), "toDate"),
        p_rep: one,
      })));

    default:
      throw new HttpError(400, "report must be one of activities, sales, orders, sku, trade, work, training, tracking");
  }
}));
