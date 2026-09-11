// =============================================================================
// GET  /customer-registrations?statuses=…&query=…
// GET  /customer-registrations?customerId=…
// POST /customer-registrations
//        { "action": "update", "registration": { … } }
//        { "action": "photo",  "customerId": …, "typeId": …, "storagePath": … }
//        { "action": "delete", "customerId": … }
//
// What the rep filed and what became of it. The legacy spreads this over five
// endpoints -- FetchNewCustomerList, FetchNewCustomerID, FetchNewCustomerImage,
// UpdateCustomerInfo, DelNewCustomer -- but the images always travel with the
// detail and the three writes are three moves on one object, so it is one route
// with a verb.
//
// Registering a *new* outlet stays on /submit-customer: that one creates a row
// and this one only ever revisits one.
// =============================================================================

import { handler, HttpError, json, unwrap } from "../_shared/client.ts";

const STATUSES = ["pending", "approved", "rejected"] as const;

interface Body {
  action?: string;
  customerId?: string;
  typeId?: string;
  storagePath?: string;
  takenAt?: string;
  lat?: number;
  lng?: number;
  fileSize?: number;
  registration?: Record<string, unknown>;
}

function list(value: string | null): string[] | undefined {
  if (!value) return undefined;
  const parts = value.split(",").map((s) => s.trim()).filter(Boolean);
  return parts.length > 0 ? parts : undefined;
}

Deno.serve(handler(async (req, db) => {
  if (req.method === "GET") {
    const params = new URL(req.url).searchParams;

    const customerId = params.get("customerId");
    if (customerId) {
      const detail = unwrap(
        await db.rpc("customer_registration_detail", { p_customer_id: customerId }),
      );
      // Null means it is not this rep's registration. Not found and not yours
      // are the same answer on purpose.
      if (!detail) throw new HttpError(404, "registration not found");
      return json({ registration: detail });
    }

    const statuses = list(params.get("statuses"));
    statuses?.forEach((s) => {
      if (!STATUSES.includes(s as typeof STATUSES[number])) {
        throw new HttpError(400, `${s} is not an approval status`);
      }
    });

    const [page, types] = await Promise.all([
      db.rpc("customer_registrations_for", {
        p_statuses: statuses,
        p_query: params.get("query") || null,
        p_limit: Number(params.get("limit") ?? 50),
        p_offset: Number(params.get("offset") ?? 0),
      }),
      // The photo slots travel with the list so the edit form never waits for
      // them; there are four rows and they change about once a year.
      db.rpc("customer_photo_types"),
    ]);

    const body = unwrap(page) as {
      total: number;
      counts: Record<string, number>;
      items: unknown[];
    };

    return json({ ...body, photo_types: unwrap(types) });
  }

  if (req.method !== "POST") throw new HttpError(405, "GET or POST only");

  const body = await req.json().catch(() => {
    throw new HttpError(400, "body must be JSON");
  }) as Body;

  switch (body.action) {
    case "update": {
      if (!body.registration) throw new HttpError(400, "registration is required");
      return json({
        registration: unwrap(
          await db.rpc("update_customer_registration", { payload: body.registration }),
        ),
      });
    }

    case "photo": {
      if (!body.customerId) throw new HttpError(400, "customerId is required");
      if (!body.typeId) throw new HttpError(400, "typeId is required");
      if (!body.storagePath) throw new HttpError(400, "storagePath is required");

      return json({
        registration: unwrap(
          await db.rpc("set_customer_registration_photo", {
            p_customer_id: body.customerId,
            p_type_id: body.typeId,
            p_storage_path: body.storagePath,
            p_taken_at: body.takenAt,
            p_lat: body.lat ?? null,
            p_lng: body.lng ?? null,
            p_file_size: body.fileSize ?? null,
          }),
        ),
      });
    }

    case "delete": {
      if (!body.customerId) throw new HttpError(400, "customerId is required");
      unwrap(
        await db.rpc("delete_customer_registration", { p_customer_id: body.customerId }),
      );
      // Nothing to hand back: the thing the caller was holding is gone.
      return json({ registration: null });
    }

    default:
      throw new HttpError(400, "action must be update, photo or delete");
  }
}));
