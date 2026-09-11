// =============================================================================
// POST /submit-work-plan
//
//   { "action": "save",   "plan": { … } }             create or edit a draft
//   { "action": "status", "planId": …, "status": …,
//                         "reason": … }               drive the state machine
//   { "action": "delete", "planId": … }               throw away a draft
//
// One endpoint for three verbs because they are three moves on the same object
// and the client always knows which it is making. Each returns the plan as it
// now stands, so the screen never has to guess what the server decided.
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

interface Body {
  action?: string;
  planId?: string;
  status?: string;
  reason?: string;
  plan?: Record<string, unknown>;
}

Deno.serve(handler(async (req, db) => {
  if (req.method !== "POST") throw new HttpError(405, "POST only");

  const body = await req.json().catch(() => {
    throw new HttpError(400, "body must be JSON");
  }) as Body;

  switch (body.action) {
    case "save": {
      if (!body.plan) throw new HttpError(400, "plan is required");

      const planId = unwrap(
        await db.rpc("save_work_plan", { payload: body.plan }),
      ) as string;

      return json({
        plan: unwrap(await db.rpc("work_plan_detail", { p_plan_id: planId })),
      });
    }

    case "status": {
      if (!body.planId) throw new HttpError(400, "planId is required");
      if (!body.status || !STATUSES.includes(body.status as typeof STATUSES[number])) {
        throw new HttpError(400, "status must be one of " + STATUSES.join(", "));
      }

      unwrap(await db.rpc("set_work_plan_status", {
        p_plan_id: body.planId,
        p_status: body.status,
        p_reason: body.reason ?? null,
      }));

      return json({
        plan: unwrap(await db.rpc("work_plan_detail", { p_plan_id: body.planId })),
      });
    }

    case "delete": {
      if (!body.planId) throw new HttpError(400, "planId is required");
      unwrap(await db.rpc("delete_work_plan", { p_plan_id: body.planId }));
      // Nothing to hand back: the thing the caller was holding is gone.
      return json({ plan: null });
    }

    default:
      throw new HttpError(400, "action must be save, status or delete");
  }
}));
