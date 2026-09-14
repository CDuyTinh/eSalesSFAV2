// =============================================================================
// GET  /trade-registrations?kinds=…&programIds=…&statuses=…&query=…
// GET  /trade-registrations?customerId=…      what that outlet may still join
// GET  /trade-registrations?pickCustomers=…   the outlets a rep may sign up
// POST /trade-registrations   { kind, customerId, programId, levelId, portion }
//
// Đăng ký chương trình. The legacy spreads this over eight endpoints across two
// controllers; they are three questions and one write, and the filters all come
// off the same list.
//
// Registering from inside a visit stays where it was — the customer hub calls
// submit-display-registration / submit-loyalty-registration directly. This route
// is the off-visit way in, and the database function now accepts either.
// =============================================================================

import { handler, HttpError, json, unwrap } from "../_shared/client.ts";

const KINDS = ["display", "loyalty"] as const;
type Kind = typeof KINDS[number];

const STATUSES = ["pending", "approved", "rejected"] as const;

function list(value: string | null): string[] | undefined {
  if (!value) return undefined;
  const parts = value.split(",").map((s) => s.trim()).filter(Boolean);
  return parts.length > 0 ? parts : undefined;
}

interface Body {
  kind?: string;
  customerId?: string;
  programId?: string;
  levelId?: string;
  portion?: number;
  reason?: string;
}

Deno.serve(handler(async (req, db) => {
  if (req.method === "GET") {
    const params = new URL(req.url).searchParams;

    // The outlets this rep may sign up. Same set the work-plan picker uses:
    // the rep's own routes, which is what the registration check tests against.
    const pick = params.get("pickCustomers");
    if (pick !== null) {
      return json({
        customers: unwrap(
          await db.rpc("plannable_customers", { p_query: pick || null }),
        ),
      });
    }

    const customerId = params.get("customerId");
    if (customerId) {
      return json(
        unwrap(await db.rpc("trade_programs_open_for", { p_customer_id: customerId })),
      );
    }

    const kinds = list(params.get("kinds"));
    kinds?.forEach((k) => {
      if (!KINDS.includes(k as Kind)) throw new HttpError(400, `${k} is not a kind`);
    });

    const statuses = list(params.get("statuses"));
    statuses?.forEach((s) => {
      if (!STATUSES.includes(s as typeof STATUSES[number])) {
        throw new HttpError(400, `${s} is not a status`);
      }
    });

    return json(
      unwrap(await db.rpc("trade_registrations_for", {
        p_kinds: kinds,
        p_program_ids: list(params.get("programIds")),
        p_statuses: statuses,
        p_query: params.get("query") || null,
        p_limit: Number(params.get("limit") ?? 50),
        p_offset: Number(params.get("offset") ?? 0),
      })),
    );
  }

  if (req.method !== "POST") throw new HttpError(405, "GET or POST only");

  const body = await req.json().catch(() => {
    throw new HttpError(400, "body must be JSON");
  }) as Body;

  if (!body.kind || !KINDS.includes(body.kind as Kind)) {
    throw new HttpError(400, "kind must be display or loyalty");
  }
  if (!body.customerId) throw new HttpError(400, "customerId is required");
  if (!body.programId) throw new HttpError(400, "programId is required");
  if (!body.levelId) throw new HttpError(400, "levelId is required");

  const payload = {
    customer_id: body.customerId,
    program_id: body.programId,
    level_id: body.levelId,
    portion: body.portion ?? 1,
    ...(body.reason ? { reason: body.reason } : {}),
  };

  const id = unwrap(
    await db.rpc(
      body.kind === "display"
        ? "submit_display_registration"
        : "submit_loyalty_registration",
      { p_payload: payload },
    ),
  );

  return json({ id });
}));
