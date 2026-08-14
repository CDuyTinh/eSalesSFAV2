package com.tinhcd.myesalessfa.data.remote

import com.tinhcd.myesalessfa.data.remote.http.PostgrestException
import com.tinhcd.myesalessfa.data.remote.http.orThrow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.MediaType.Companion.toMediaType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/**
 * Asserts the requests Retrofit builds, not the server's answers.
 *
 * These URLs were verified by hand against the live project before the migration:
 * `visit_weekdays=cs.{5}` returned the six Friday stops, and the upsert form
 * returned 201 then 200 without duplicating. The point of these tests is that the
 * annotations keep producing exactly those requests — that is the part a refactor
 * can silently break, and with no device to run the app on it is otherwise
 * unchecked.
 */
class PostgrestServiceTest {

    private lateinit var server: MockWebServer
    private lateinit var service: PostgrestService

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }
        service = Retrofit.Builder()
            .baseUrl(server.url("/rest/v1/"))
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(PostgrestService::class.java)
    }

    @After
    fun tearDown() = server.close()

    private fun enqueueJson(body: String) {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "application/json")
                .body(body)
                .build(),
        )
    }

    private fun enqueueEmpty(code: Int) {
        server.enqueue(MockResponse.Builder().code(code).build())
    }

    @Test
    fun `route query asks for the weekday as an array-contains filter`() = runTest {
        enqueueJson("[]")
        service.routeCustomers(visitWeekdays = Filters.arrayContains(5))

        val path = server.takeRequest().target
        // Braces arrive percent-encoded, which is what PostgREST expects.
        assertTrue("visit_weekdays missing in $path", path.contains("visit_weekdays=cs.%7B5%7D"))
        assertTrue(path.contains("is_active=eq.true"))
        assertTrue(path.contains("order=visit_order.asc"))
        // The embedded customer resource, which is what makes one round trip do.
        assertTrue(path.contains("customer%3Acustomer_id") || path.contains("customer:customer_id"))
    }

    @Test
    fun `step results are filtered by visit and ask only for what the DTO needs`() = runTest {
        enqueueJson("[]")
        service.stepResults(visitId = Filters.eq("v1"))

        val path = server.takeRequest().target
        assertTrue(path.startsWith("/rest/v1/visit_step_result?"))
        assertTrue(path.contains("visit_id=eq.v1"))
        assertTrue(path.contains("form_id%2Ccompleted_at") || path.contains("form_id,completed_at"))
    }

    @Test
    fun `the step result upsert carries the merge preference and conflict key`() = runTest {
        enqueueEmpty(200)
        service.upsertStepResult(buildJsonObject { put("form_id", "feedback") })

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertTrue(request.target.contains("on_conflict=visit_id%2Cform_id"))
        // Both preferences travel in one header, as verified against the live API.
        assertEquals(
            "resolution=merge-duplicates,return=minimal",
            request.headers["Prefer"],
        )
    }

    @Test
    fun `a check-out is a PATCH filtered by id, not a blanket update`() = runTest {
        // A missing filter here would rewrite every visit row the rep can reach.
        enqueueEmpty(200)
        service.updateVisit(
            id = Filters.eq("visit-1"),
            patch = buildJsonObject { put("status", "completed") },
        )

        val request = server.takeRequest()
        assertEquals("PATCH", request.method)
        assertTrue("no id filter in ${request.target}", request.target.contains("id=eq.visit-1"))
    }

    @Test
    fun `the previous count query excludes the visit in progress and takes one row`() = runTest {
        enqueueJson("[]")
        service.stockCounts(
            customerId = Filters.eq("c1"),
            exceptVisitId = Filters.neq("v1"),
        )

        val path = server.takeRequest().target
        assertTrue(path.contains("customer_id=eq.c1"))
        // neq, not eq: comparing against the attempt being replaced would report
        // roughly zero sales every time a rep recounted.
        assertTrue(path.contains("visit_id=neq.v1"))
        assertTrue(path.contains("order=count_date.desc%2Ccreated_at.desc"))
        assertTrue(path.contains("limit=1"))
    }

    @Test
    fun `functions post to the rpc path`() = runTest {
        enqueueEmpty(200)
        service.submitOrder(buildJsonObject { put("p_order", buildJsonObject { }) })
        assertEquals("/rest/v1/rpc/submit_order", server.takeRequest().target.substringBefore('?'))

        enqueueEmpty(200)
        service.submitStockCount(buildJsonObject { put("p_count", buildJsonObject { }) })
        assertEquals(
            "/rest/v1/rpc/submit_stock_count",
            server.takeRequest().target.substringBefore('?'),
        )
    }

    @Test
    fun `catalogue reads request the columns their DTOs decode`() = runTest {
        enqueueJson("[]")
        service.products()
        val products = server.takeRequest().target
        assertTrue(products.contains("vat_basis_points"))
        assertTrue(products.contains("is_active=eq.true"))

        enqueueJson("[]")
        service.productUoms()
        assertTrue(server.takeRequest().target.contains("conversion_rate"))

        enqueueJson("[]")
        service.priceList()
        val prices = server.takeRequest().target
        // No filter: RLS decides which classes the rep may price against.
        assertTrue(prices.contains("class_id"))
        assertTrue(prices.contains("from_date"))
    }

    @Test
    fun `a failed function surfaces the message the database raised`() = runTest {
        // This is the shape the live API returned for a bad submit_order call.
        // The guards inside the RPCs are only useful if their message reaches the
        // rep, and Exception.toAppError() keys off the exception message.
        server.enqueue(
            MockResponse.Builder()
                .code(400)
                .addHeader("Content-Type", "application/json")
                .body(
                    """{"code":"P0001","details":null,"hint":null,
                        "message":"order abc: 1 of 1 lines could not be priced for 2026-08-14"}""",
                )
                .build(),
        )

        val thrown = runCatching {
            service.submitOrder(buildJsonObject { put("p_order", buildJsonObject { }) }).orThrow()
        }.exceptionOrNull()

        val error = thrown as? PostgrestException
        assertEquals(400, error?.status)
        assertEquals("P0001", error?.code)
        assertTrue(
            "lost the database message: ${error?.message}",
            error?.message?.contains("could not be priced") == true,
        )
    }

    @Test
    fun `an error with no parseable body still names the status`() = runTest {
        // A gateway or proxy failure returns HTML or nothing at all. "HTTP 502" is
        // poor, but it beats a JSON decode exception masking the real failure.
        server.enqueue(MockResponse.Builder().code(502).body("<html>bad gateway</html>").build())

        val thrown = runCatching {
            service.submitStockCount(buildJsonObject { }).orThrow()
        }.exceptionOrNull() as? PostgrestException

        assertEquals(502, thrown?.status)
        assertTrue(thrown?.message?.isNotBlank() == true)
    }

    @Test
    fun `a decoded read maps onto its DTO`() = runTest {
        // Guards the DTO against a rename on either side: the field names here are
        // the ones the live API returned during verification.
        enqueueJson(
            """[{"product_id":"p1","uom_code":"CASE","class_id":null,"price":222000,
                 "from_date":"2026-01-01","to_date":"2099-12-31"}]""",
        )
        val row = service.priceList().single()

        assertEquals("p1", row.productId)
        assertEquals("CASE", row.uomCode)
        assertEquals(null, row.classId)
        assertEquals(222_000L, row.price)
        assertEquals("2026-01-01", row.fromDate)
    }
}
