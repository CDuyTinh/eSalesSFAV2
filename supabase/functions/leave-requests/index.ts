// =============================================================================
// GET    /leave-requests        -> the rep's requests, plus the leave types
// POST   /leave-requests        -> ask for time off
// PATCH  /leave-requests        -> withdraw one that is still pending
//
// No approve. There is no supervisor role in this app, and the trigger on the
// table refuses any status change from a signed-in user other than withdrawing a
// pending request — so an endpoint offering approval would be an endpoint that
// could only fail.
// =============================================================================

import { handler, HttpError, json, unwrap } from "../_shared/client.ts";

interface CreateBody {
  leave_type_id?: string;
  from_date?: string;
  to_date?: string;
  reason?: string;
}

interface WithdrawBody {
  request_id?: string;
}

Deno.serve(handler(async (req, db) => {
  if (req.method === "GET") {
    return json(unwrap(await db.rpc("leave_requests")));
  }

  if (req.method === "POST") {
    const body = await req.json() as CreateBody;

    if (!body.leave_type_id) throw new HttpError(400, "chọn loại nghỉ");
    if (!body.from_date || !body.to_date) throw new HttpError(400, "chọn thời gian nghỉ");

    const reason = body.reason?.trim();
    if (!reason) throw new HttpError(400, "nhập lý do nghỉ");
    if (body.to_date < body.from_date) {
      throw new HttpError(400, "ngày kết thúc phải sau ngày bắt đầu");
    }

    const me = (unwrap(
      await db.from("salesperson").select("id,branch_id"),
    ) as { id: string; branch_id: string }[])[0];
    if (!me) throw new HttpError(403, "no salesperson is linked to this account");

    const inserted = await db.from("leave_request").insert({
      salesperson_id: me.id,
      branch_id: me.branch_id,
      leave_type_id: body.leave_type_id,
      from_date: body.from_date,
      to_date: body.to_date,
      reason,
    }).select("id");

    // The exclusion constraint is what refuses an overlap; this only translates
    // its answer. "conflicting key value violates exclusion constraint" is true
    // and tells a rep nothing about which week is already spoken for.
    if (inserted.error?.code === "23P01") {
      throw new HttpError(409, "bạn đã có đơn nghỉ trùng khoảng thời gian này");
    }
    unwrap(inserted);

    return json({ ok: true });
  }

  if (req.method === "PATCH") {
    const body = await req.json() as WithdrawBody;
    if (!body.request_id) throw new HttpError(400, "request_id is required");

    const updated = await db.from("leave_request")
      .update({ status: "cancelled" })
      .eq("id", body.request_id)
      .eq("status", "pending")
      .select("id");

    // Raised by the transition trigger when the row is no longer pending.
    if (updated.error?.code === "P0001") {
      throw new HttpError(409, updated.error.message);
    }
    const rows = unwrap(updated) as { id: string }[];

    // Matched nothing: already decided, already withdrawn, or not the rep's.
    if (rows.length === 0) {
      throw new HttpError(409, "đơn này không còn ở trạng thái chờ duyệt");
    }

    return json({ ok: true });
  }

  throw new HttpError(405, "GET, POST or PATCH only");
}));
