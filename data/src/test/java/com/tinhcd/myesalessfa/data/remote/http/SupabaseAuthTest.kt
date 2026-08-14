package com.tinhcd.myesalessfa.data.remote.http

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * The token bridge is the part of the Retrofit move with no equivalent in the
 * Supabase SDK path: the SDK attached and refreshed the JWT itself. Since the app
 * cannot be run on this machine, these tests are what stand behind it.
 */
class SupabaseAuthTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() = server.close()

    /** Hands out tokens in sequence and counts how often a refresh was asked for. */
    private class FakeTokens(
        private val sequence: MutableList<String?>,
    ) : SessionTokens {
        val refreshes = AtomicInteger(0)
        private var currentToken: String? = sequence.removeFirst()

        override fun current(): String? = currentToken

        override suspend fun refresh() {
            refreshes.incrementAndGet()
            if (sequence.isNotEmpty()) currentToken = sequence.removeFirst()
        }
    }

    private fun clientWith(tokens: SessionTokens) = OkHttpClient.Builder()
        .addInterceptor(SupabaseAuthInterceptor(SupabaseApiKey("anon-key"), tokens))
        .authenticator(SupabaseTokenAuthenticator(tokens))
        .build()

    private fun get() = Request.Builder().url(server.url("/rest/v1/product")).build()

    @Test
    fun `every request carries the project key and the current token`() {
        server.enqueue(MockResponse.Builder().code(200).body("[]").build())

        clientWith(FakeTokens(mutableListOf("token-1"))).newCall(get()).execute().close()

        val headers = server.takeRequest().headers
        assertEquals("anon-key", headers["apikey"])
        assertEquals("Bearer token-1", headers["Authorization"])
    }

    @Test
    fun `signed out sends the key alone rather than failing locally`() {
        // RLS refusing the call is a clearer signal than an exception that hides
        // which request was attempted.
        server.enqueue(MockResponse.Builder().code(401).build())

        clientWith(FakeTokens(mutableListOf(null))).newCall(get()).execute().close()

        val headers = server.takeRequest().headers
        assertEquals("anon-key", headers["apikey"])
        assertNull(headers["Authorization"])
    }

    @Test
    fun `a 401 triggers one refresh and the retry carries the new token`() {
        server.enqueue(MockResponse.Builder().code(401).build())
        server.enqueue(MockResponse.Builder().code(200).body("[]").build())

        val tokens = FakeTokens(mutableListOf("expired", "fresh"))
        val response = clientWith(tokens).newCall(get()).execute()

        assertEquals(200, response.code)
        response.close()
        assertEquals(1, tokens.refreshes.get())

        assertEquals("Bearer expired", server.takeRequest().headers["Authorization"])
        assertEquals("Bearer fresh", server.takeRequest().headers["Authorization"])
    }

    @Test
    fun `a refresh that yields nothing new gives up instead of looping`() {
        // Retrying the same credentials forever would hang the rep on a dead
        // session. OkHttp stops as soon as the authenticator returns null.
        server.enqueue(MockResponse.Builder().code(401).build())

        val tokens = FakeTokens(mutableListOf("expired"))
        val response = clientWith(tokens).newCall(get()).execute()

        assertEquals(401, response.code)
        response.close()
        assertEquals(1, tokens.refreshes.get())
    }

    @Test
    fun `concurrent 401s refresh once, not once each`() = runTest {
        // Supabase rotates the refresh token on use, so a second concurrent
        // refresh would present an already-invalidated token and sign the rep out.
        // Three parallel reads is an ordinary screen load, not a stress case.
        //
        // The server is modelled on the header rather than a fixed queue: it
        // rejects the expired token and accepts the fresh one, whatever order the
        // three calls happen to arrive in. A blind queue of 401s would instead
        // reject a request that was sent *after* the refresh, which is not
        // something a real server does.
        server.dispatcher = object : mockwebserver3.Dispatcher() {
            override fun dispatch(request: mockwebserver3.RecordedRequest): MockResponse =
                if (request.headers["Authorization"] == "Bearer fresh") {
                    MockResponse.Builder().code(200).body("[]").build()
                } else {
                    MockResponse.Builder().code(401).build()
                }
        }

        val tokens = FakeTokens(mutableListOf("expired", "fresh"))
        val client = clientWith(tokens)

        val codes = (1..3).map {
            async(kotlinx.coroutines.Dispatchers.IO) {
                client.newCall(get()).execute().use { it.code }
            }
        }.awaitAll()

        assertEquals(listOf(200, 200, 200), codes)
        assertEquals(1, tokens.refreshes.get())
    }

    @Test
    fun `a request that already has the newest token is retried without refreshing`() {
        // The queued-behind case: another request refreshed while this one waited,
        // so the token it carried is stale but the session is already healthy.
        val tokens = object : SessionTokens {
            val refreshes = AtomicInteger(0)
            override fun current(): String = "fresh"
            override suspend fun refresh() {
                refreshes.incrementAndGet()
            }
        }

        val authenticator = SupabaseTokenAuthenticator(tokens)
        val staleRequest = Request.Builder()
            .url(server.url("/rest/v1/product"))
            .header("Authorization", "Bearer expired")
            .build()

        val retry = authenticator.authenticate(
            null,
            okhttp3.Response.Builder()
                .request(staleRequest)
                .protocol(okhttp3.Protocol.HTTP_1_1)
                .code(401)
                .message("Unauthorized")
                .build(),
        )

        assertNotNull(retry)
        assertEquals("Bearer fresh", retry?.header("Authorization"))
        assertEquals(0, tokens.refreshes.get())
    }
}
