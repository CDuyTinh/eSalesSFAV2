// =============================================================================
// POST /submit-step
//
// Records a completed workflow step.
//
// Upsert on (visit_id, form_id): the outbox may replay an entry that already
// landed, and a step is either done or not — recording it twice is not a
// different fact.
//
// Note that `take_order` and `stock_outlet` do not come through here. Those steps
// are completed inside their own database functions, in the same transaction as
// the order or the count, so that a delivered order can never leave the rep still
// owing the step.
// =============================================================================

import { handler, HttpError, json, unwrap } from "../_shared/client.ts";

interface Body {
  visit_id?: string;
  form_id?: string;
  completed_at?: string;
  payload?: Record<string, unknown>;
}

Deno.serve(handler(async (req, db) => {
  if (req.method !== "POST") throw new HttpError(405, "POST only");

  const body = await req.json() as Body;
  if (!body.visit_id) throw new HttpError(400, "visit_id is required");
  if (!body.form_id) throw new HttpError(400, "form_id is required");

  unwrap(
    await db.from("visit_step_result")
      .upsert(
        {
          visit_id: body.visit_id,
          form_id: body.form_id,
          completed_at: body.completed_at ?? new Date().toISOString(),
          payload: body.payload ?? {},
        },
        { onConflict: "visit_id,form_id" },
      )
      .select("id"),
  );

  return json({ ok: true });
}));
