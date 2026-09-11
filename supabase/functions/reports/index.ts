// =============================================================================
// GET /reports?activities=YYYY-MM-DD
// GET /reports?sales=YYYY-MM-DD
// GET /reports?kind=orders|sku|trade|work_result&fromDate=…&toDate=…
//
// All six reports the legacy menu has, behind one route.
//
// The first two were here already and keep their own parameter names, because
// the client that calls them is shipped and renaming them would buy nothing.
// The other four arrive under `kind` — they are four shapes of the same
// question, what did this rep do between these two dates, and the argument that
// put the first two together ("two deployments for two lines of routing would
// be two chances to drift") only gets stronger at six.
//
// Thin throughout: every report is assembled in SQL next to the rows, and this
// picks which question was asked and hands back the answer.
// =============================================================================

import { handler, HttpError, json, unwrap } from "../_shared/client.ts";

const KINDS = ["orders", "sku", "trade", "work_result"] as const;
type Kind = typeof KINDS[number];

const ORDER_STATUSES = ["new", "confirmed", "cancelled"] as const;

function date(value: string | null, name: string): string | undefined {
  if (!value) return undefined;
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value)) {
    throw new HttpError(400, `${name} is not a date`);
  }
  return value;
}

function list(value: string | null): string[] | undefined {
  if (!value) return undefined;
  const parts = value.split(",").map((s) => s.trim()).filter(Boolean);
  return parts.length > 0 ? parts : undefined;
}

Deno.serve(handler(async (req, db) => {
  if (req.method !== "GET") throw new HttpError(405, "GET only");

  const params = new URL(req.url).searchParams;
  const activities = params.get("activities");
  const sales = params.get("sales");
  const kind = params.get("kind") as Kind | null;

  if ([activities, sales, kind].filter(Boolean).length > 1) {
    throw new HttpError(400, "ask for one report, not several");
  }

  if (activities) {
    return json(unwrap(await db.rpc("report_activities", { p_date: activities })));
  }

  if (sales) {
    // Any day in the month; the function truncates. The client sends the first,
    // but a date in the middle must not silently return a different month's
    // figures, so the truncation lives on one side only.
    return json(unwrap(await db.rpc("report_sales", { p_month: sales })));
  }

  if (!kind) {
    throw new HttpError(
      400,
      "activities, sales, or kind (" + KINDS.join(", ") + ") is required",
    );
  }

  if (!KINDS.includes(kind)) {
    throw new HttpError(400, "kind must be one of " + KINDS.join(", "));
  }

  const window = {
    p_from_date: date(params.get("fromDate"), "fromDate"),
    p_to_date: date(params.get("toDate"), "toDate"),
  };

  switch (kind) {
    case "orders": {
      const statuses = list(params.get("statuses"));
      statuses?.forEach((s) => {
        if (!ORDER_STATUSES.includes(s as typeof ORDER_STATUSES[number])) {
          throw new HttpError(400, `${s} is not an order status`);
        }
      });

      const inRoute = params.get("inRoute");

      return json(
        unwrap(await db.rpc("report_orders", {
          ...window,
          p_statuses: statuses,
          p_in_route: inRoute === null ? undefined : inRoute === "true",
          p_limit: Number(params.get("limit") ?? 50),
          p_offset: Number(params.get("offset") ?? 0),
        })),
      );
    }

    case "sku":
      // A set-returning function rather than one object, so it is wrapped to
      // match the others — a client that unwraps one kind differently is a
      // client that will get it wrong for the fifth.
      return json({
        items: unwrap(await db.rpc("report_sku", {
          ...window,
          p_query: params.get("query") || null,
        })),
      });

    case "trade":
      return json(unwrap(await db.rpc("report_trade", window)));

    case "work_result":
      return json(
        unwrap(await db.rpc("report_work_result", {
          ...window,
          p_category_ids: list(params.get("categoryIds")),
        })),
      );
  }
}));
