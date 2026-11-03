// =============================================================================
// Push notifications through FCM HTTP v1.
//
// The service account (secret FCM_SERVICE_ACCOUNT, the JSON Firebase issued)
// signs a JWT that Google exchanges for an hour's access token. Tokens are read
// with the service role: the sender is told about other people's phones, which
// no rep's or manager's own client may read.
//
// Sending never fails the request it follows. A leave request that was saved
// is saved whether or not the supervisor's phone heard about it, so a push is
// scheduled after the response (EdgeRuntime.waitUntil) and its errors logged.
// =============================================================================

import { createClient, SupabaseClient } from "jsr:@supabase/supabase-js@2";

export type App = "sfa" | "manager";

export interface PushMessage {
  title: string;
  body: string;
  /** Which screen a tap should open; the apps map it to a destination. */
  screen?: string;
}

interface ServiceAccount {
  project_id: string;
  client_email: string;
  private_key: string;
  token_uri: string;
}

let admin: SupabaseClient | null = null;
function adminClient(): SupabaseClient {
  admin ??= createClient(
    Deno.env.get("SUPABASE_URL")!,
    Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!,
    { auth: { persistSession: false } },
  );
  return admin;
}

function serviceAccount(): ServiceAccount | null {
  const raw = Deno.env.get("FCM_SERVICE_ACCOUNT");
  if (!raw) return null;
  try {
    return JSON.parse(raw) as ServiceAccount;
  } catch {
    console.error("FCM_SERVICE_ACCOUNT is not valid JSON");
    return null;
  }
}

const b64url = (bytes: Uint8Array) =>
  btoa(String.fromCharCode(...bytes)).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
const b64urlText = (text: string) => b64url(new TextEncoder().encode(text));

let cached: { token: string; expiresAt: number } | null = null;

/** An OAuth access token for FCM, reused until a minute before it lapses. */
async function accessToken(sa: ServiceAccount): Promise<string> {
  const now = Math.floor(Date.now() / 1000);
  if (cached && cached.expiresAt - 60 > now) return cached.token;

  const header = b64urlText(JSON.stringify({ alg: "RS256", typ: "JWT" }));
  const claims = b64urlText(JSON.stringify({
    iss: sa.client_email,
    scope: "https://www.googleapis.com/auth/firebase.messaging",
    aud: sa.token_uri,
    iat: now,
    exp: now + 3600,
  }));

  const pem = sa.private_key.replace(/-----[^-]+-----/g, "").replace(/\s+/g, "");
  const der = Uint8Array.from(atob(pem), (c) => c.charCodeAt(0));
  const key = await crypto.subtle.importKey(
    "pkcs8", der, { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" }, false, ["sign"],
  );
  const signature = new Uint8Array(
    await crypto.subtle.sign("RSASSA-PKCS1-v1_5", key, new TextEncoder().encode(`${header}.${claims}`)),
  );

  const res = await fetch(sa.token_uri, {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer",
      assertion: `${header}.${claims}.${b64url(signature)}`,
    }),
  });
  if (!res.ok) throw new Error(`FCM auth failed: ${res.status} ${await res.text()}`);
  const body = await res.json() as { access_token: string; expires_in: number };
  cached = { token: body.access_token, expiresAt: now + body.expires_in };
  return body.access_token;
}

async function send(to: string[], app: App, msg: PushMessage): Promise<void> {
  const sa = serviceAccount();
  if (!sa || to.length === 0) return;

  const db = adminClient();
  const { data, error } = await db.from("device_token").select("token")
    .in("salesperson_id", to).eq("app", app);
  if (error) throw new Error(error.message);
  const tokens = (data ?? []).map((r) => r.token as string);
  if (tokens.length === 0) return;

  const auth = await accessToken(sa);
  const url = `https://fcm.googleapis.com/v1/projects/${sa.project_id}/messages:send`;

  await Promise.all(tokens.map(async (token) => {
    const res = await fetch(url, {
      method: "POST",
      headers: { Authorization: `Bearer ${auth}`, "Content-Type": "application/json" },
      body: JSON.stringify({
        message: {
          token,
          notification: { title: msg.title, body: msg.body },
          data: { screen: msg.screen ?? "" },
          android: { priority: "high", notification: { channel_id: "general" } },
        },
      }),
    });
    if (res.ok) return;
    const text = await res.text();
    // The app was uninstalled or the token rotated: this phone will never
    // hear on it again, so it is dropped rather than tried on every event.
    if (res.status === 404 || text.includes("UNREGISTERED")) {
      await db.from("device_token").delete().eq("token", token);
      return;
    }
    console.error(`FCM send failed: ${res.status} ${text}`);
  }));
}

/** Sends after the response has gone; see the header for why. */
export function push(to: (string | null | undefined)[], app: App, msg: PushMessage): void {
  const ids = [...new Set(to.filter((id): id is string => !!id))];
  const job = send(ids, app, msg).catch((e) => console.error("push failed:", e));
  // deno-lint-ignore no-explicit-any
  const runtime = (globalThis as any).EdgeRuntime;
  if (runtime?.waitUntil) runtime.waitUntil(job);
}

/**
 * Tells the rep's direct supervisor, in the manager app, about something the
 * rep sent for approval. [msg] is given the rep's name.
 */
export async function pushToSupervisor(repId: string, msg: (repName: string) => PushMessage): Promise<void> {
  try {
    const { data } = await adminClient().from("salesperson").select("full_name,supervisor_id")
      .eq("id", repId).maybeSingle();
    if (data?.supervisor_id) push([data.supervisor_id as string], "manager", msg(data.full_name as string));
  } catch (e) {
    console.error("push failed:", e);
  }
}

/** 2026-11-03 → 03/11/2026, for a message body. */
export const viDate = (iso: string) => iso.slice(0, 10).split("-").reverse().join("/");

/** Whose request an approval item is (leave, customer, display, loyalty, posm). */
export async function approvalOwner(kind: string, id: string): Promise<string | null> {
  const { data } = await adminClient().rpc("approval_owner", { p_kind: kind, p_id: id });
  return (data as string | null) ?? null;
}
