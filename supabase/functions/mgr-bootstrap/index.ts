// =============================================================================
// GET /mgr-bootstrap
//
// The eSalesManager app's first call after sign-in: who the manager is, who
// reports to them, and the shared settings. `mgr-` marks functions owned by the
// manager app (repo eSalesManagerV2); they live here because this is the one
// place the shared database's migrations and functions are kept.
//
// The caller's own row is told apart from the team rows by user_id: RLS lets a
// manager read both, so "the first row" would no longer be the caller.
// A rep calling this gets their own row with position 'sr' and an empty team;
// refusing them is the app's job, so the reason it shows is a precise one.
// =============================================================================

import { handler, HttpError, json, unwrap } from "../_shared/client.ts";

const STAFF_COLUMNS =
  "id,code,full_name,position,branch_id,phone,supervisor_id,branch:branch_id(id,code,name)";

interface KeyValue {
  key: string;
  value: string;
}

interface Staff {
  id: string;
  supervisor_id: string | null;
}

Deno.serve(handler(async (_req, db) => {
  const { data: auth, error } = await db.auth.getUser();
  if (error || !auth?.user) throw new HttpError(401, "invalid session");

  const [me, settings] = await Promise.all([
    db.from("salesperson").select(STAFF_COLUMNS)
      .eq("user_id", auth.user.id).maybeSingle().then(unwrap),
    db.from("app_setting").select("key,value").then(unwrap),
  ]);

  const manager = me as Staff | null;

  // The whole subtree: an SS gets their reps, an AM their SSs and those SSs'
  // reps, an RM everything below. supervisor_id and position let the app tell
  // the levels apart. Asked by id rather than left to RLS, which also shows
  // other rows (the caller's own, for one) for reasons of its own.
  const teamIds = manager ? unwrap(await db.rpc("team_salesperson_ids")) as string[] : [];
  const team = teamIds.length > 0
    ? await db.from("salesperson").select(STAFF_COLUMNS)
      .in("id", teamIds).eq("is_active", true)
      .order("code").then(unwrap)
    : [];

  return json({
    manager,
    team,
    settings: Object.fromEntries(
      (settings as KeyValue[]).map((r) => [r.key, r.value]),
    ),
  });
}));
