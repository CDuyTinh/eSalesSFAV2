// =============================================================================
// GET  /nfc-tag?uid=…                    which customer a tag belongs to
// POST /nfc-tag { customer_id, tag_uid } bind a tag to the customer being visited
//
// NFC tags fixed inside outlets, as a proof of presence that does not depend
// on GPS. Both directions go through database functions that own the rules:
// a tag is bound only by a rep checked in at that customer, never moved from
// one customer to another, and looked up only within the caller's branch.
//
// The GET answers 200 with `owner: null` for a tag nobody registered: an
// unknown tag is an ordinary answer the app shows, not a failure.
// =============================================================================

import { handler, HttpError, json, unwrap } from "../_shared/client.ts";

interface Body {
  customer_id?: string;
  tag_uid?: string;
}

Deno.serve(handler(async (req, db) => {
  if (req.method === "GET") {
    const uid = new URL(req.url).searchParams.get("uid");
    if (!uid) throw new HttpError(400, "uid is required");
    return json({ owner: unwrap(await db.rpc("nfc_tag_customer", { p_tag_uid: uid })) });
  }

  if (req.method === "POST") {
    const body = await req.json() as Body;
    if (!body.customer_id) throw new HttpError(400, "customer_id is required");
    if (!body.tag_uid) throw new HttpError(400, "tag_uid is required");
    return json(unwrap(
      await db.rpc("register_nfc_tag", {
        p_customer_id: body.customer_id,
        p_tag_uid: body.tag_uid,
      }),
    ));
  }

  throw new HttpError(405, "GET or POST only");
}));
