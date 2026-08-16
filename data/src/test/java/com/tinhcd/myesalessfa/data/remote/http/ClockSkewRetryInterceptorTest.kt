package com.tinhcd.myesalessfa.data.remote.http

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ClockSkewRetryInterceptorTest {

    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = OkHttpClient.Builder()
            .addInterceptor(ClockSkewRetryInterceptor())
            .build()
    }

    @After
    fun tearDown() = server.close()

    private fun enqueue(code: Int, body: String) {
        server.enqueue(
            MockResponse.Builder()
                .code(code)
                .addHeader("Content-Type", "application/json")
                .body(body)
                .build(),
        )
    }

    private fun call() = client.newCall(
        Request.Builder().url(server.url("/functions/v1/bootstrap")).build(),
    ).execute()

    @Test
    fun `retries once when the token is refused as issued in the future`() {
        // The real message, as Postgres reports it through the Edge Function.
        enqueue(400, """{"message":"JWT issued at future"}""")
        enqueue(200, """{"settings":{}}""")

        call().use { assertEquals(200, it.code) }
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `gives up after one retry rather than waiting forever`() {
        // A second failure is not skew, and hiding it behind more waiting would
        // turn a reportable error into a hang.
        enqueue(400, """{"message":"JWT issued at future"}""")
        enqueue(400, """{"message":"JWT issued at future"}""")

        call().use { assertEquals(400, it.code) }
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `leaves an unrelated 400 alone`() {
        enqueue(400, """{"message":"1 of 1 lines could not be priced"}""")

        call().use { response ->
            assertEquals(400, response.code)
            // The body must still be readable: peeking it must not consume it,
            // or every error message would arrive empty.
            assertTrue(response.body.string().contains("could not be priced"))
        }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `leaves a success alone`() {
        enqueue(200, """{"settings":{}}""")

        call().use { assertEquals(200, it.code) }
        assertEquals(1, server.requestCount)
    }
}
