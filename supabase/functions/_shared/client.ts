// =============================================================================
// Shared helpers for every Edge Function in this project.
//
// The single rule that matters here: functions run *outside* Postgres, so they
// are outside RLS unless they put themselves back inside it. Every function
// below builds its database client from the caller's own Authorization header,
// which means `auth.uid()` resolves to the signed-in rep and every policy still
// applies exactly as it did when the app talked to PostgREST directly.
//
// The service_role key is deliberately never read. It would bypass RLS, and RLS
// is the only thing protecting one rep's customers from another's — there is no
// second layer behind it to catch a mistake.
// =============================================================================

import { createClient, SupabaseClient } from "jsr:@supabase/supabase-js@2";

/** Thrown to produce a specific HTTP status instead of a generic 500. */
export class HttpError extends Error {
  constructor(readonly status: number, message: string) {
    super(message);
  }
}

/**
 * A database client acting as the caller.
 *
 * Rejects a request with no bearer token rather than quietly falling back to the
 * anon role: an unauthenticated read would return an empty result, and an empty
 * route looks identical to "no customers today".
 */
export function clientFor(req: Request): SupabaseClient {
  const authorization = req.headers.get("Authorization");
  if (!authorization) {
    throw new HttpError(401, "missing Authorization header");
  }

  return createClient(
    Deno.env.get("SUPABASE_URL")!,
    Deno.env.get("SUPABASE_ANON_KEY")!,
    {
      global: { headers: { Authorization: authorization } },
      auth: { persistSession: false, autoRefreshToken: false },
    },
  );
}

export function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

/**
 * Wraps a handler so every function reports failures the same way.
 *
 * The body shape mirrors PostgREST's (`{ message }`), so the Android side's
 * existing error mapping keeps working without a second parser.
 */
export function handler(
  fn: (req: Request, db: SupabaseClient) => Promise<Response>,
): (req: Request) => Promise<Response> {
  return async (req: Request) => {
    try {
      return await fn(req, clientFor(req));
    } catch (e) {
      if (e instanceof HttpError) {
        return json({ message: e.message }, e.status);
      }
      const message = e instanceof Error ? e.message : String(e);
      return json({ message }, 500);
    }
  };
}

/** Raises on a PostgREST error so handlers do not each have to check. */
export function unwrap<T>(
  result: { data: T | null; error: { message: string } | null },
): T {
  if (result.error) throw new HttpError(400, result.error.message);
  return result.data as T;
}

/**
 * Reads every row of a query in pages.
 *
 * PostgREST caps a single response, so a catalogue that outgrows that cap would
 * otherwise arrive silently truncated — the app would run on a partial product
 * list with nothing to indicate it. Paging here is what makes "the whole
 * catalogue" actually mean that.
 */
export async function readAll<T>(
  page: (from: number, to: number) => PromiseLike<
    { data: T[] | null; error: { message: string } | null }
  >,
  pageSize = 1000,
): Promise<T[]> {
  const all: T[] = [];
  for (let from = 0; ; from += pageSize) {
    const rows = unwrap(await page(from, from + pageSize - 1));
    all.push(...rows);
    if (rows.length < pageSize) return all;
  }
}
