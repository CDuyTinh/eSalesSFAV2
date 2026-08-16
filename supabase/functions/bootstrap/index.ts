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

// Nested two levels deep on purpose. The client caches each questionnaire as one
// JSON row and only ever reads it whole, so a flat shape would only be reassembled
// into this one on arrival.
const SURVEY_COLUMNS =
  "id,code,name,form_id,pass_score," +
  "groups:survey_question_group(name,sort_order," +
  "questions:survey_question(id,code,content,answer_type,is_required,score,sort_order," +
  "options:survey_question_option(id,code,content,score,sort_order)))";

interface KeyValue {
  key: string;
  value: string;
}

Deno.serve(handler(async (req, db) => {
  // Only the languages actually seeded are honoured; anything else falls back to
  // Vietnamese rather than returning an empty label set.
  const requested = new URL(req.url).searchParams.get("lang") ?? "vi";
  const lang = requested === "en" ? "en" : "vi";

  const [
    salesperson,
    settings,
    reasonCodes,
    salesSteps,
    translations,
    surveys,
    menu,
  ] = await Promise.all([
    db.from("salesperson").select(SALESPERSON_COLUMNS).then(unwrap),
    db.from("app_setting").select("key,value").then(unwrap),
    db.from("reason_code").select("id,code,name,kind").eq("is_active", true)
      .order("name").then(unwrap),
    db.from("sales_step")
      .select("form_id,step,title_key,is_required,config")
      .eq("is_active", true).order("step").then(unwrap),
    db.from("translation").select("key,value").eq("lang_code", lang)
      .then(unwrap),
    db.from("survey_type").select(SURVEY_COLUMNS).eq("is_active", true)
      .order("code").then(unwrap),
    // Flat, with parent_code carrying the nesting. The client is going to key it
    // by parent anyway to render a bar and three sheets, and a nested shape here
    // would only be flattened again on arrival.
    db.from("menu_item")
      .select("code,parent_code,title_key,sort_order")
      .eq("is_active", true)
      .order("sort_order").then(unwrap),
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
    // Sorted here rather than on the device: PostgREST does not order embedded rows,
    // and a questionnaire whose questions shuffle between screen loads is one no rep
    // can work through.
    surveys: sortSurveys(surveys as SurveyType[]),
    menu,
  });
}));

interface Sortable {
  sort_order?: number;
}

interface SurveyType {
  groups?: (Sortable & { questions?: (Sortable & { options?: Sortable[] })[] })[];
}

function bySortOrder(a: Sortable, b: Sortable): number {
  return (a.sort_order ?? 0) - (b.sort_order ?? 0);
}

function sortSurveys(surveys: SurveyType[]): SurveyType[] {
  for (const survey of surveys) {
    survey.groups?.sort(bySortOrder);
    for (const group of survey.groups ?? []) {
      group.questions?.sort(bySortOrder);
      for (const question of group.questions ?? []) {
        question.options?.sort(bySortOrder);
      }
    }
  }
  return surveys;
}
