// =============================================================================
// POST   /device-token { token, app: "sfa" | "manager" }  → this phone gets pushes
// DELETE /device-token { token }                          → it stops (sign-out)
//
// Shared by both apps. The token is filed against whoever the caller's JWT
// names; register_device_token moves it if someone else had this phone before.
// =============================================================================

import { handler, HttpError, json, unwrap } from "../_shared/client.ts";

interface Body {
  token?: string;
  app?: string;
}

Deno.serve(handler(async (req, db) => {
  const body = await req.json() as Body;
  if (!body.token) throw new HttpError(400, "token is required");

  if (req.method === "POST") {
    if (body.app !== "sfa" && body.app !== "manager") {
      throw new HttpError(400, "app must be sfa or manager");
    }
    unwrap(await db.rpc("register_device_token", { p_token: body.token, p_app: body.app }));
    return json({ ok: true });
  }

  if (req.method === "DELETE") {
    unwrap(await db.rpc("unregister_device_token", { p_token: body.token }));
    return json({ ok: true });
  }

  throw new HttpError(405, "POST or DELETE only");
}));
