package com.tinhcd.myesalessfa.data.remote

import com.tinhcd.myesalessfa.data.outbox.OrderLinePayload
import com.tinhcd.myesalessfa.data.outbox.OrderPayload
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/**
 * Asserts the requests Retrofit builds and that the DTOs decode the functions'
 * actual output.
 *
 * The JSON fixtures below are trimmed copies of real responses captured from the
 * deployed functions while verifying them, not invented shapes. That is what makes
 * them worth having: the app cannot be run on this machine, so a rename on either
 * side of this boundary would otherwise only surface on a device.
 */
class FunctionsServiceTest {

    private lateinit var server: MockWebServer
    private lateinit var service: FunctionsService

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }
        service = Retrofit.Builder()
            .baseUrl(server.url("/functions/v1/"))
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(FunctionsService::class.java)
    }

    @After
    fun tearDown() = server.close()

    private fun enqueue(body: String, code: Int = 200) {
        server.enqueue(
            MockResponse.Builder()
                .code(code)
                .addHeader("Content-Type", "application/json")
                .body(body)
                .build(),
        )
    }

    // -------------------------------------------------------------------------
    // Request shapes
    // -------------------------------------------------------------------------

    @Test
    fun `reads hit their function with the parameters the function requires`() = runTest {
        enqueue("""{"settings":{},"translations":{}}""")
        service.bootstrap("vi")
        assertEquals("/functions/v1/bootstrap?lang=vi", server.takeRequest().target)

        enqueue("""{"generated_at":"now","products":[],"price_rules":[]}""")
        service.catalogue()
        assertEquals("/functions/v1/catalogue", server.takeRequest().target)

        enqueue("""{"date":"2026-08-14","stops":[]}""")
        service.route("2026-08-14")
        assertEquals("/functions/v1/route?date=2026-08-14", server.takeRequest().target)

        enqueue("""{"visit_id":"v1","completions":[]}""")
        service.visitWorkflow("v1")
        assertEquals("/functions/v1/visit-workflow?visitId=v1", server.takeRequest().target)
    }

    @Test
    fun `the previous count request excludes the visit in progress`() = runTest {
        // Comparing against the attempt being replaced would report roughly zero
        // sales every time a rep recounted, so exceptVisitId is not optional.
        enqueue("""{"count_date":null,"previous":{}}""")
        service.previousCount(customerId = "c1", exceptVisitId = "v1")

        val target = server.takeRequest().target
        assertTrue(target.contains("customerId=c1"))
        assertTrue(target.contains("exceptVisitId=v1"))
    }

    @Test
    fun `writes post to their own function`() = runTest {
        enqueue("""{"ok":true}""")
        service.submitStep(buildJsonObject { put("form_id", "feedback") })
        val step = server.takeRequest()
        assertEquals("POST", step.method)
        assertEquals("/functions/v1/submit-step", step.target)

        enqueue("""{"ok":true}""")
        service.submitCheckout(buildJsonObject { put("visit_id", "v1") })
        assertEquals("/functions/v1/submit-checkout", server.takeRequest().target)

        enqueue("""{"order_id":"o1"}""")
        service.submitOrder(order())
        assertEquals("/functions/v1/submit-order", server.takeRequest().target)
    }

    @Test
    fun `the order body uses the database function's own field names`() = runTest {
        // The payload travels from the outbox through Deno into plpgsql untouched.
        // A camelCase key here would be silently ignored by submit_order, which
        // would then reject the order as having no lines.
        enqueue("""{"order_id":"o1"}""")
        service.submitOrder(order())

        val body = server.takeRequest().body?.utf8().orEmpty()
        assertTrue("missing visit_id in $body", body.contains("\"visit_id\""))
        assertTrue(body.contains("\"client_total_amount\""))
        assertTrue(body.contains("\"line_no\""))
        assertTrue(body.contains("\"uom_code\""))
        assertTrue("camelCase leaked into $body", !body.contains("visitId"))
    }

    // -------------------------------------------------------------------------
    // Decoding real payloads
    // -------------------------------------------------------------------------

    @Test
    fun `bootstrap decodes settings and translations as maps`() = runTest {
        enqueue(
            """{"salesperson":{"id":"sp1","code":"nvbh01","full_name":"Tran Van Nam",
                 "branch_id":"br1","branch":{"id":"br1","code":"BR01","name":"NPP Mien Dong"}},
                 "settings":{"gps_checkin_radius_m":"100","require_stock_before_order":"true"},
                 "reason_codes":[{"id":"r1","code":"PRICE","name":"Che gia cao","kind":"no_order"}],
                 "sales_steps":[{"form_id":"outside_checking","step":1,
                   "title_key":"step_outside_checking","is_required":true,"config":{}}],
                 "lang":"vi","translations":{"step_take_order":"Dat hang"}}""",
        )

        val boot = service.bootstrap("vi")

        assertEquals("nvbh01", boot.salesperson?.code)
        assertEquals("BR01", boot.salesperson?.branch?.code)
        // Read by key, which is the reason the function shapes them this way.
        assertEquals("100", boot.settings["gps_checkin_radius_m"])
        assertEquals("true", boot.settings["require_stock_before_order"])
        assertEquals("Dat hang", boot.translations["step_take_order"])
        assertEquals("outside_checking", boot.salesSteps.single().formId)
        assertTrue(boot.salesSteps.single().isRequired)
    }

    @Test
    fun `an unprovisioned account decodes as a null salesperson, not a failure`() = runTest {
        // Authenticated but with no salesperson row. The app treats this as a
        // failed login rather than dropping the rep into an app with no branch.
        enqueue("""{"salesperson":null,"settings":{},"translations":{}}""")
        assertNull(service.bootstrap("vi").salesperson)
    }

    @Test
    fun `catalogue decodes units nested inside their product`() = runTest {
        enqueue(
            """{"generated_at":"2026-08-14T12:52:40.127Z","products":[
                 {"id":"c08","code":"BK003","name":"Keo Alpenliebe goi 105g","base_uom":"PCS",
                  "vat_basis_points":800,"category_name":"Banh keo","category_sort":2,
                  "units":[
                    {"uom_code":"PCS","uom_name":"Le","conversion_rate":1,
                     "is_default_sale":false,"sort_order":1},
                    {"uom_code":"PACK","uom_name":"Lock","conversion_rate":10,
                     "is_default_sale":false,"sort_order":2},
                    {"uom_code":"CASE","uom_name":"Thung","conversion_rate":100,
                     "is_default_sale":true,"sort_order":3}]}],
                 "price_rules":[
                   {"product_id":"c01","uom_code":"CASE","class_id":"class-a","price":222000,
                    "from_date":"2026-01-01","to_date":"2099-12-31"},
                   {"product_id":"c01","uom_code":"CASE","class_id":null,"price":228000,
                    "from_date":"2026-01-01","to_date":"2099-12-31"}]}""",
        )

        val catalogue = service.catalogue()
        val product = catalogue.products.single()

        assertEquals(800, product.vatBasisPoints)
        assertEquals(listOf("PCS", "PACK", "CASE"), product.units.map { it.uomCode })
        assertEquals(100, product.units.last().conversionRate)
        assertTrue(product.units.last().isDefaultSale)

        // Rules, not resolved prices: both the class row and the list row arrive,
        // because the price only exists relative to a customer.
        assertEquals(2, catalogue.priceRules.size)
        assertEquals("class-a", catalogue.priceRules.first().classId)
        assertNull(catalogue.priceRules.last().classId)
    }

    @Test
    fun `catalogue decodes the must-stock lists with their scoping intact`() = runTest {
        // Trimmed from the deployed function's real output. The wildcard nulls are
        // load-bearing: they are how a national list is expressed, and losing them
        // in decoding would silently scope the core list to nobody.
        enqueue(
            """{"generated_at":"now","products":[],"price_rules":[],"msl":[
                 {"id":"f01","code":"CORE","channel_id":null,"shop_type_id":null,
                  "from_date":"2026-01-01","to_date":"2099-12-31",
                  "items":[{"product_id":"c01","min_base_qty":24},
                           {"product_id":"c03","min_base_qty":48}]},
                 {"id":"f02","code":"GT","channel_id":"ch-gt","shop_type_id":null,
                  "from_date":"2026-01-01","to_date":"2099-12-31",
                  "items":[{"product_id":"c01","min_base_qty":48}]}]}""",
        )

        val lists = service.catalogue().msl
        assertEquals(listOf("CORE", "GT"), lists.map { it.code })

        val core = lists.first()
        assertNull(core.channelId)
        assertNull(core.shopTypeId)
        assertEquals(24, core.items.first { it.productId == "c01" }.minBaseQty)

        // The channel list demands more of the same SKU; the union rule in :domain
        // resolves that to 48.
        assertEquals("ch-gt", lists.last().channelId)
        assertEquals(48, lists.last().items.single().minBaseQty)
    }

    @Test
    fun `a project with no must-stock lists decodes as none, not as a failure`() = runTest {
        enqueue("""{"generated_at":"now","products":[],"price_rules":[]}""")
        assertTrue(service.catalogue().msl.isEmpty())
    }

    @Test
    fun `route carries the segment the must-stock lists are scoped by`() = runTest {
        enqueue(
            """{"date":"2026-08-14","stops":[{"visit_order":1,
                 "customer":{"id":"c1","code":"KH001","name":"Tap hoa Minh Anh",
                   "class_id":"class-a","channel_id":"ch-gt","shop_type_id":"shop-th"},
                 "visit_id":null,"status":"planned"}]}""",
        )

        val customer = service.route("2026-08-14").stops.single().customer
        assertEquals("ch-gt", customer.channelId)
        assertEquals("shop-th", customer.shopTypeId)
    }

    @Test
    fun `an uncategorised product sorts last rather than first`() = runTest {
        // category_sort defaults high so a product with no category does not lead
        // the catalogue the rep scrolls.
        enqueue(
            """{"generated_at":"now","products":[
                 {"id":"p1","code":"X","name":"X","base_uom":"PCS","vat_basis_points":1000}],
                 "price_rules":[]}""",
        )
        val product = service.catalogue().products.single()
        assertEquals(9999, product.categorySort)
        assertNull(product.categoryName)
        assertTrue(product.units.isEmpty())
    }

    @Test
    fun `route decodes a stop that has not been checked into yet`() = runTest {
        enqueue(
            """{"date":"2026-08-14","stops":[
                 {"visit_order":1,"customer":{"id":"c1","code":"KH001","name":"Tap hoa Minh Anh",
                   "class_id":"class-a"},"visit_id":null,"status":"planned",
                  "check_in_at":null,"check_out_at":null}]}""",
        )

        val stop = service.route("2026-08-14").stops.single()
        assertEquals(1, stop.visitOrder)
        assertEquals("KH001", stop.customer.code)
        // The class drives which price list applies once an order is written.
        assertEquals("class-a", stop.customer.classId)
        assertNull(stop.visitId)
        assertEquals("planned", stop.status)
    }

    @Test
    fun `previous count decodes the per-product totals`() = runTest {
        enqueue("""{"count_date":"2026-08-13","previous":{"c01":120,"c03":3}}""")
        val previous = service.previousCount("c1", "v1")

        assertEquals("2026-08-13", previous.countDate)
        assertEquals(mapOf("c01" to 120, "c03" to 3), previous.previous)
    }

    @Test
    fun `a never-counted outlet is distinguishable from an all-zero count`() = runTest {
        enqueue("""{"count_date":null,"previous":{}}""")
        assertNull(service.previousCount("c1", "v1").countDate)
    }

    // -------------------------------------------------------------------------
    // Errors
    // -------------------------------------------------------------------------

    @Test
    fun `a guard inside the database function reaches the caller as its own message`() = runTest {
        // Captured from the deployed function: the plpgsql RAISE travels through
        // Deno unchanged, and Exception.toAppError() keys off this message.
        enqueue(
            """{"message":"order 999: 1 of 1 lines could not be priced for 2026-08-14"}""",
            code = 400,
        )

        val thrown = runCatching { service.submitOrder(order()).orThrow() }
            .exceptionOrNull() as? PostgrestException

        assertEquals(400, thrown?.status)
        assertTrue(thrown?.message?.contains("could not be priced") == true)
    }

    @Test
    fun `a foreign visit is refused and says so`() = runTest {
        enqueue("""{"message":"visit v9 is not a visit of this salesperson"}""", code = 404)

        val thrown = runCatching {
            service.submitCheckout(buildJsonObject { put("visit_id", "v9") }).orThrow()
        }.exceptionOrNull() as? PostgrestException

        assertEquals(404, thrown?.status)
        assertTrue(thrown?.message?.contains("not a visit of this salesperson") == true)
    }

    @Test
    fun `an unparseable error body still names the status`() = runTest {
        server.enqueue(MockResponse.Builder().code(502).body("<html>bad gateway</html>").build())

        val thrown = runCatching {
            service.submitStockCount(stockCount()).orThrow()
        }.exceptionOrNull() as? PostgrestException

        assertEquals(502, thrown?.status)
        assertTrue(thrown?.message?.isNotBlank() == true)
    }

    private fun order() = OrderPayload(
        id = "o1",
        visitId = "v1",
        orderDate = "2026-08-14",
        clientTotalAmount = 609_360,
        clientCreatedAt = "2026-08-14T09:00:00Z",
        lines = listOf(
            OrderLinePayload(lineNo = 1, productId = "c01", uomCode = "CASE", qty = 2),
        ),
    )

    private fun stockCount() = com.tinhcd.myesalessfa.data.outbox.StockCountPayload(
        id = "s1",
        visitId = "v1",
        countDate = "2026-08-14",
        clientCreatedAt = "2026-08-14T09:00:00Z",
        lines = emptyList(),
    )
}
