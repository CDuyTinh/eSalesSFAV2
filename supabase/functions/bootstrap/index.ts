// =============================================================================
// GET /bootstrap?lang=vi
//
// Everything the app needs cached after sign-in, in one response.
//
// Replaces five round trips — salesperson, app_setting, reason_code, sales_step,
// translation — which the client made one after another while the rep waited on a
// login screen. On the connection a rep actually has, the round trips cost more
// than the bytes.
//
// Settings and translations come back as objects keyed by name rather than as row
// arrays. Both are only ever read by key, and shaping them here removes the
// list-to-map step the client was doing on every refresh.
// =============================================================================

import { handler, json, unwrap } from "../_shared/client.ts";

const SALESPERSON_COLUMNS =
  "id,code,full_name,branch_id,branch:branch_id(id,code,name)";

interface KeyValue {
  key: string;
  value: string;
}

Deno.serve(handler(async (req, db) => {
  // Only the languages actually seeded are honoured; anything else falls back to
  // Vietnamese rather than returning an empty label set.
  const requested = new URL(req.url).searchParams.get("lang") ?? "vi";
  const lang = requested === "en" ? "en" : "vi";

  const [salesperson, settings, reasonCodes, salesSteps, translations] =
    await Promise.all([
      db.from("salesperson").select(SALESPERSON_COLUMNS).then(unwrap),
      db.from("app_setting").select("key,value").then(unwrap),
      db.from("reason_code").select("id,code,name,kind").eq("is_active", true)
        .order("name").then(unwrap),
      db.from("sales_step")
        .select("form_id,step,title_key,is_required,config")
        .eq("is_active", true).order("step").then(unwrap),
      db.from("translation").select("key,value").eq("lang_code", lang)
        .then(unwrap),
    ]);

  const toMap = (rows: KeyValue[]) =>
    Object.fromEntries(rows.map((r) => [r.key, r.value]));

  return json({
    // RLS narrows this to the caller's own row, so there is exactly one — or none
    // when the auth user has no salesperson record, which the client treats as an
    // unprovisioned account rather than a failed login.
    salesperson: (salesperson as unknown[])[0] ?? null,
    settings: toMap(settings as KeyValue[]),
    reason_codes: reasonCodes,
    sales_steps: salesSteps,
    lang,
    translations: toMap(translations as KeyValue[]),
  });
}));
