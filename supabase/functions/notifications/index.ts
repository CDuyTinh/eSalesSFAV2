// =============================================================================
// GET /notifications?fromDate=…&toDate=…&unreadOnly=…
//
// The bell. API_GetPPC_Notify and API_GetCountNotify, which over there are two
// round trips and here are one: the badge is a number the list already knows,
// and asking twice is how a badge ends up disagreeing with the screen under it.
// =============================================================================

import { handler, HttpError, json, unwrap } from "../_shared/client.ts";

type Kind =
  | "promotion"
  | "manual_promotion"
  | "display"
  | "loyalty"
  | "posm"
  | "work_note";

interface Row {
  kind: Kind;
  source_id: string;
  title: string;
  body: string;
  code: string | null;
  from_date: string | null;
  to_date: string | null;
  is_read: boolean;
  read_at: string | null;
}

/** A missing date means the function's own default window, not an error. */
function date(value: string | null): string | undefined {
  if (!value) return undefined;
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value)) {
    throw new HttpError(400, `${value} is not a date`);
  }
  return value;
}

Deno.serve(handler(async (req, db) => {
  if (req.method !== "GET") throw new HttpError(405, "GET only");

  const params = new URL(req.url).searchParams;
  const window = {
    p_from_date: date(params.get("fromDate")),
    p_to_date: date(params.get("toDate")),
  };

  const rows = unwrap(
    await db.rpc("notifications_for", {
      ...window,
      p_unread_only: params.get("unreadOnly") === "true",
    }),
  ) as Row[];

  return json({
    items: rows,
    // Counted over the same window regardless of unreadOnly, so the badge says
    // the same thing whichever way the rep has the list filtered.
    unread: unwrap(await db.rpc("unread_notification_count", window)) as number,
  });
}));
