// =============================================================================
// GET    /work-notes[?status=open|done]  -> the rep's notes
// POST   /work-notes                     -> add one
// PATCH  /work-notes                     -> close one, with its outcome
// DELETE /work-notes?id=…                -> remove one
//
// The rep's own scratchpad, so every verb is here rather than only reads and
// appends. RLS scopes all four to the author; this function never filters by
// salesperson itself, which is the same division of labour as everywhere else.
// =============================================================================

import { handler, HttpError, json, unwrap } from "../_shared/client.ts";

interface CreateBody {
  title?: string;
  body?: string | null;
  due_on?: string | null;
  customer_id?: string | null;
}

interface CloseBody {
  note_id?: string;
  result?: string;
}

Deno.serve(handler(async (req, db) => {
  if (req.method === "GET") {
    const status = new URL(req.url).searchParams.get("status");
    if (status && status !== "open" && status !== "done") {
      throw new HttpError(400, "status must be open or done");
    }
    return json({ notes: unwrap(await db.rpc("work_notes", { p_status: status })) });
  }

  if (req.method === "POST") {
    const body = await req.json() as CreateBody;
    const title = body.title?.trim();
    if (!title) throw new HttpError(400, "nhập nội dung công việc");

    const me = (unwrap(
      await db.from("salesperson").select("id"),
    ) as { id: string }[])[0];
    if (!me) throw new HttpError(403, "no salesperson is linked to this account");

    unwrap(
      await db.from("work_note").insert({
        salesperson_id: me.id,
        customer_id: body.customer_id ?? null,
        title,
        body: body.body?.trim() || null,
        due_on: body.due_on ?? null,
      }).select("id"),
    );

    return json({ ok: true });
  }

  if (req.method === "PATCH") {
    const body = await req.json() as CloseBody;
    if (!body.note_id) throw new HttpError(400, "note_id is required");

    const result = body.result?.trim();
    // Refused here as well as by the check constraint. The constraint would
    // report a violation the rep cannot read; this says what to do about it.
    if (!result) throw new HttpError(400, "nhập kết quả trước khi hoàn thành");

    const updated = unwrap(
      await db.from("work_note").update({
        status: "done",
        result,
        done_at: new Date().toISOString(),
      }).eq("id", body.note_id).select("id"),
    ) as { id: string }[];

    // RLS makes another rep's note invisible rather than forbidden, so an update
    // that matched nothing is the same answer as no such note.
    if (updated.length === 0) throw new HttpError(404, "không tìm thấy công việc");

    return json({ ok: true });
  }

  if (req.method === "DELETE") {
    const id = new URL(req.url).searchParams.get("id");
    if (!id) throw new HttpError(400, "id is required");

    unwrap(await db.from("work_note").delete().eq("id", id).select("id"));
    return json({ ok: true });
  }

  throw new HttpError(405, "GET, POST, PATCH or DELETE only");
}));
