// =============================================================================
// POST /read-notification
//
//   { "kind": "display", "sourceId": "…" }   one item        ReadNotification
//   { "all": true, "fromDate": …, "toDate": … }   everything ReadAllNotification
//
// Both return the unread count that is left, because the caller's next move is
// always to repaint the badge.
// =============================================================================

import { handler, HttpError, json, unwrap } from "../_shared/client.ts";

const KINDS = [
  "promotion",
  "manual_promotion",
  "display",
  "loyalty",
  "posm",
  "work_note",
] as const;

interface Body {
  kind?: string;
  sourceId?: string;
  all?: boolean;
  fromDate?: string;
  toDate?: string;
}

Deno.serve(handler(async (req, db) => {
  if (req.method !== "POST") throw new HttpError(405, "POST only");

  const body = await req.json().catch(() => {
    throw new HttpError(400, "body must be JSON");
  }) as Body;

  const window = { p_from_date: body.fromDate, p_to_date: body.toDate };
  let marked = 0;

  if (body.all) {
    marked = unwrap(await db.rpc("mark_all_notifications_read", window)) as number;
  } else {
    if (!body.kind || !KINDS.includes(body.kind as typeof KINDS[number])) {
      throw new HttpError(400, "kind must be one of " + KINDS.join(", "));
    }
    if (!body.sourceId) throw new HttpError(400, "sourceId is required");

    unwrap(await db.rpc("mark_notification_read", {
      p_kind: body.kind,
      p_source_id: body.sourceId,
    }));
    marked = 1;
  }

  return json({
    marked,
    unread: unwrap(await db.rpc("unread_notification_count", window)) as number,
  });
}));
