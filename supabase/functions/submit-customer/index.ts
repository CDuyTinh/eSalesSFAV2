// =============================================================================
// POST /submit-customer
//
// Registers an outlet a rep met in the field.
//
// The rep, their branch, the code and the approval status are all decided here.
// Only the first is a repeat of the reason submit-visit gives; the other three
// are the point of the endpoint. A client that could name its own code would
// eventually name one twice, and a client that could name its own status would
// be approving its own customers.
//
// The row is inserted as the caller, not as a service role, so the insert policy
// still has to agree — the checks below are for the rep's benefit, not the
// database's.
// =============================================================================

import { handler, HttpError, json, unwrap } from "../_shared/client.ts";

interface Body {
  name?: string;
  phone?: string | null;
  address?: string | null;
  ward_id?: string | null;
  lat?: number | null;
  lng?: number | null;
  class_id?: string | null;
  channel_id?: string | null;
  shop_type_id?: string | null;
  note?: string | null;
}

Deno.serve(handler(async (req, db) => {
  if (req.method !== "POST") throw new HttpError(405, "POST only");

  const body = await req.json() as Body;

  const name = body.name?.trim();
  if (!name) throw new HttpError(400, "tên cửa hàng là bắt buộc");

  const address = body.address?.trim();
  if (!address) throw new HttpError(400, "địa chỉ là bắt buộc");

  const me = (unwrap(
    await db.from("salesperson").select("id,branch_id"),
  ) as { id: string; branch_id: string }[])[0];

  if (!me) throw new HttpError(403, "no salesperson is linked to this account");

  const code = unwrap(
    await db.rpc("next_customer_registration_code", { p_branch_id: me.branch_id }),
  ) as string;

  const inserted = unwrap(
    await db.from("customer").insert({
      code,
      name,
      branch_id: me.branch_id,
      phone: body.phone?.trim() || null,
      address,
      ward_id: body.ward_id ?? null,
      lat: body.lat ?? null,
      lng: body.lng ?? null,
      class_id: body.class_id ?? null,
      channel_id: body.channel_id ?? null,
      shop_type_id: body.shop_type_id ?? null,
      approval_status: "pending",
      created_by_salesperson_id: me.id,
      registration_note: body.note?.trim() || null,
    }).select("id,code,name"),
  ) as { id: string; code: string; name: string }[];

  return json({ ok: true, customer: inserted[0] });
}));
