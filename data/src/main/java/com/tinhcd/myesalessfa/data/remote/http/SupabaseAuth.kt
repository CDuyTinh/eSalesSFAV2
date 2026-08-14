package com.tinhcd.myesalessfa.data.remote.http

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Authenticator
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import javax.inject.Inject
import javax.inject.Singleton

private const val HEADER_API_KEY = "apikey"
private const val HEADER_AUTH = "Authorization"
private const val BEARER = "Bearer "

/**
 * The session, as the HTTP layer needs to see it.
 *
 * An interface rather than a direct call into the Supabase SDK so the retry logic
 * below can be tested without standing up a real client — `client.auth` is an
 * extension property on a final class and is not mockable.
 */
interface SessionTokens {
    /** The current access token, or null when nobody is signed in. */
    fun current(): String?

    /** Forces a refresh. Failure is not thrown: the caller decides what to do. */
    suspend fun refresh()
}

@Singleton
class SupabaseSessionTokens @Inject constructor(
    private val client: SupabaseClient,
) : SessionTokens {

    override fun current(): String? = client.auth.currentAccessTokenOrNull()

    override suspend fun refresh() {
        runCatching { client.auth.refreshCurrentSession() }
    }
}

/**
 * Stamps every data request with the project key and the signed-in rep's JWT.
 *
 * The token is read per call rather than captured once: the SDK refreshes it on
 * its own schedule, and a captured copy would go stale mid-visit. Reading it is a
 * synchronous look at a StateFlow, so doing it per request is cheap.
 *
 * A null token means nobody is signed in. The request still goes out with the
 * anon key alone and RLS refuses it — the correct answer, and a more debuggable
 * one than a client-side exception that hides which call was attempted.
 */
@Singleton
class SupabaseAuthInterceptor @Inject constructor(
    private val apiKey: SupabaseApiKey,
    private val tokens: SessionTokens,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val builder = chain.request().newBuilder()
            .header(HEADER_API_KEY, apiKey.value)

        tokens.current()?.let { builder.header(HEADER_AUTH, BEARER + it) }

        return chain.proceed(builder.build())
    }
}

/**
 * Recovers from a 401 by refreshing, then retrying once.
 *
 * The SDK normally refreshes ahead of expiry on its own, so this is the safety net
 * for when it cannot: the device slept, or was offline across the expiry, and the
 * rep's next action is what discovers it.
 *
 * Single-flight through a mutex, comparing the token the failed request actually
 * carried against the current one. Without that, a screen firing three parallel
 * reads would trigger three refreshes — and Supabase rotates the refresh token on
 * use, so the losers of that race would refresh with a token that had just been
 * invalidated, signing the rep out mid-visit.
 */
@Singleton
class SupabaseTokenAuthenticator @Inject constructor(
    private val tokens: SessionTokens,
) : Authenticator {

    private val mutex = Mutex()

    override fun authenticate(route: Route?, response: Response): Request? {
        val stale = response.request.header(HEADER_AUTH)?.removePrefix(BEARER)

        return runBlocking {
            mutex.withLock {
                val current = tokens.current()

                val fresh = if (current != null && current != stale) {
                    // Another request already refreshed while this one queued.
                    current
                } else {
                    tokens.refresh()
                    tokens.current()
                }

                // Nothing new to offer: let the 401 through rather than retrying
                // the same credentials forever.
                if (fresh == null || fresh == stale) {
                    null
                } else {
                    response.request.newBuilder()
                        .header(HEADER_AUTH, BEARER + fresh)
                        .build()
                }
            }
        }
    }
}

/**
 * Wrapper so the key is injected as a type rather than a bare String, which would
 * collide with any other injected String.
 *
 * Not a value class: Dagger cannot provide one, because the inline mangling
 * produces a JVM method name (`provideApiKey-B4exQdQ`) that is not a valid Java
 * identifier.
 */
data class SupabaseApiKey(val value: String)
