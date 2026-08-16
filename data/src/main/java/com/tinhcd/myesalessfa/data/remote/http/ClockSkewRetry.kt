package com.tinhcd.myesalessfa.data.remote.http

import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject
import javax.inject.Singleton

/** How much of the error body to look at. The message is the first field. */
private const val PEEK_BYTES = 2_048L

/**
 * The skew being waited out is under a second: `iat` is a whole-second claim, so a
 * token minted part-way through a second can read as one second ahead of a
 * validator that has not ticked over yet. Back when this call was accidentally
 * being made twice, the second of the two near-simultaneous requests came back 200
 * — the window is that narrow. A little over a second clears it with room to
 * spare, and is short enough that a rep reads it as the login being slow rather
 * than as the login having stalled.
 */
private const val RETRY_DELAY_MS = 1_200L

/**
 * The marker Postgres uses when a token's `iat` is ahead of its own clock.
 * Matched on the phrase rather than a code: PostgREST reports it as a plain
 * message with no error code attached.
 */
private const val SKEW_MARKER = "issued at future"

/**
 * Retries once when the server rejects a token as issued in the future.
 *
 * Supabase Auth mints the JWT and Postgres validates it, and their clocks are not
 * the same clock. A token used within a moment of being issued can therefore be
 * refused with "JWT issued at future" — which is what a rep hits, because the app
 * signs in and immediately asks for /bootstrap. Calling the same endpoint from a
 * desktop never reproduced it: the extra round trips there let enough time pass.
 *
 * Observed on a real device, roughly one sign-in in three, and it surfaced as
 * "signed in but no profile" on a login that was in fact fine.
 *
 * Nothing is repaired here, only waited out: the token becomes valid on its own
 * once the server's clock catches up to its `iat`. One retry, because a second
 * failure means something other than skew and should be reported rather than
 * hidden behind more waiting.
 */
@Singleton
class ClockSkewRetryInterceptor @Inject constructor() : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        if (!response.isClockSkew()) return response

        // peekBody leaves the body readable, so a response that turns out not to
        // be worth retrying is still returned intact to the caller.
        response.close()
        Thread.sleep(RETRY_DELAY_MS)
        return chain.proceed(chain.request())
    }

    private fun Response.isClockSkew(): Boolean {
        if (code != 400) return false
        val body = runCatching { peekBody(PEEK_BYTES).string() }.getOrNull() ?: return false
        return body.contains(SKEW_MARKER, ignoreCase = true)
    }
}
