// =============================================================================
// GET  /daily-targets?date=YYYY-MM-DD   -> today's stops with plan and history
// POST /daily-targets                   -> save the plan
//
// The save is an upsert on (salesperson, customer, date) because the rep revises
// before setting out: a second save is the same plan corrected, not a second
// plan. Sending it as one statement makes the whole day's figures land together,
// so a rep who edits five outlets never ends up with three saved and two not.
// =============================================================================

import { handler, HttpError, json, unwrap } from "../_shared/client.ts";

interface Entry {
  customer_id?: string;
  target_amount?: number;
}

interface Body {
  date?: string;
  targets?: Entry[];
}

Deno.serve(handler(async (req, db) => {
  if (req.method === "GET") {
    const date = new URL(req.url).searchParams.get("date");
    if (!date) throw new HttpError(400, "date is required (YYYY-MM-DD)");

    return json({ date, stops: unwrap(await db.rpc("daily_sales_targets", { p_date: date })) });
  }

  if (req.method !== "POST") throw new HttpError(405, "GET or POST only");

  const body = await req.json() as Body;
  const targets = body.targets ?? [];

  if (!body.date) throw new HttpError(400, "date is required");
  if (targets.length === 0) throw new HttpError(400, "chưa nhập chỉ tiêu nào");

  for (const t of targets) {
    if (!t.customer_id) throw new HttpError(400, "each target needs a customer_id");
    if (t.target_amount == null || t.target_amount < 0) {
      throw new HttpError(400, "chỉ tiêu không được âm");
    }
  }

  const me = (unwrap(
    await db.from("salesperson").select("id,branch_id"),
  ) as { id: string; branch_id: string }[])[0];

  if (!me) throw new HttpError(403, "no salesperson is linked to this account");

  unwrap(
    await db.from("daily_sales_target").upsert(
      targets.map((t) => ({
        salesperson_id: me.id,
        branch_id: me.branch_id,
        customer_id: t.customer_id,
        target_date: body.date,
        target_amount: t.target_amount,
      })),
      { onConflict: "salesperson_id,customer_id,target_date" },
    ).select("id"),
  );

  return json({ ok: true, count: targets.length });
}));
