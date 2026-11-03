// =============================================================================
// eSalesManager — Báo cáo
//
// GET /mgr-reports?report=activities&date=YYYY-MM-DD[&salespersonId=…]
// GET /mgr-reports?report=sales&month=YYYY-MM-01[&salespersonId=…]
// GET /mgr-reports?report=orders&fromDate=&toDate=[&statuses=new,confirmed][&salespersonId=…][&offset=0&limit=100]
// GET /mgr-reports?report=sku&fromDate=&toDate=[&query=][&salespersonId=…]
// GET /mgr-reports?report=trade&fromDate=&toDate=[&salespersonId=…]
// GET /mgr-reports?report=work&fromDate=&toDate=[&salespersonId=…]
// GET /mgr-reports?report=training&fromDate=&toDate=[&salespersonId=…]
// GET /mgr-reports?report=tracking&date=YYYY-MM-DD[&salespersonId=…][&supervisorId=…]
// GET /mgr-reports?report=photos&date=YYYY-MM-DD&salespersonId=…
//        → that rep's punch and visit photos for the day, signed for an hour
//
// The six SFA reports, asked of the team or of one rep in it. They run through
// the same report_*_of() cores the rep's own /reports uses, so a figure here is
// the figure the rep sees. Without salespersonId the whole team is reported;
// supervisorId and branchIds (comma-separated) narrow that team for an area
// or regional manager, on every report.
// =============================================================================

import { handler, HttpError, idList, json, unwrap } from "../_shared/client.ts";
import type { SupabaseClient } from "jsr:@supabase/supabase-js@2";

const ORDER_STATUSES = ["new", "confirmed", "cancelled"];

function date(value: string | null, name: string): string {
  if (!value || !/^\d{4}-\d{2}-\d{2}$/.test(value)) {
    throw new HttpError(400, `${name} is required (YYYY-MM-DD)`);
  }
  return value;
}

type Row = Record<string, unknown>;

/**
 * Adds a signed url beside every storage path the rows carry, in one call. The
 * caller's own client signs, so the storage policy decides what a manager may
 * see, as it does for the approvals' photos.
 */
async function signPaths(db: SupabaseClient, rows: Row[], fields: string[]): Promise<Row[]> {
  const paths = [...new Set(rows.flatMap((r) => fields.map((f) => r[f]).filter((p): p is string => typeof p === "string")))];
  if (paths.length === 0) return rows;
  const { data } = await db.storage.from("visit-photos").createSignedUrls(paths, 60 * 60);
  const urls = new Map((data ?? []).map((s) => [s.path, s.signedUrl]));
  return rows.map((r) => {
    const out: Row = { ...r };
    for (const f of fields) {
      const p = r[f];
      out[f.replace(/_path$/, "_url")] = typeof p === "string" ? urls.get(p) ?? null : null;
    }
    return out;
  });
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

  if (params.get("report") === "photos") {
    if (!one) throw new HttpError(400, "salespersonId is required");
    const day = ruled(await db.rpc("mgr_staff_photos", {
      p_salesperson_id: one, p_date: date(params.get("date"), "date"),
    })) as { punches: Row[]; visits: Row[] };
    return json({
      punches: await signPaths(db, day.punches, ["photo_path"]),
      visits: await signPaths(db, day.visits, ["check_in_photo_path", "check_out_photo_path"]),
    });
  }
  const sps: string[] = one
    ? [one]
    // The team's reps, optionally those under one supervisor or at some branches
    // (AM/RM filter): managers sell nothing, so they have no place in a sales report.
    : (ruled(await db.rpc("team_reps", {
      p_supervisor: params.get("supervisorId") || null,
      p_branches: idList(params.get("branchIds")),
    })) as string[]);
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
        // A page at a time: the team's month runs to hundreds of orders.
        p_limit: Math.min(Math.max(Number(params.get("limit") ?? 100) || 100, 1), 200),
        p_offset: Math.max(Number(params.get("offset") ?? 0) || 0, 0),
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
