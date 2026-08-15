package com.tinhcd.myesalessfa.domain

import com.tinhcd.myesalessfa.domain.model.PricedProduct
import com.tinhcd.myesalessfa.domain.model.PricedUnit
import com.tinhcd.myesalessfa.domain.model.Product
import com.tinhcd.myesalessfa.domain.model.SaleUnit
import com.tinhcd.myesalessfa.domain.model.orderSuggestions
import com.tinhcd.myesalessfa.domain.model.splitShortfall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OrderSuggestionTest {

    private fun unit(
        uom: String,
        uomName: String,
        rate: Int,
        price: Long,
        isDefault: Boolean = false,
    ) = PricedUnit(SaleUnit(uom, uomName, rate, isDefault = isDefault, sortOrder = 1), price)

    /** Sold by the case only, which is the shape most of the seed data has. */
    private fun priced(
        id: String,
        code: String,
        rate: Int,
        price: Long = 228_000,
        uom: String = "CASE",
        uomName: String = "Thung",
    ) = PricedProduct(
        product = Product(id, code, "Product $code", "Nuoc giai khat", "PCS", 1000),
        units = listOf(unit(uom, uomName, rate, price, isDefault = true)),
    )

    /** Sold by the case and singly, so a shortfall can be covered exactly. */
    private fun pricedLoose(
        id: String,
        code: String,
        caseRate: Int = 24,
        casePrice: Long = 228_000,
        piecePrice: Long = 10_000,
    ) = PricedProduct(
        product = Product(id, code, "Product $code", "Nuoc giai khat", "PCS", 1000),
        units = listOf(
            unit("CASE", "Thung", caseRate, casePrice, isDefault = true),
            unit("PCS", "Chai", 1, piecePrice),
        ),
    )

    // -------------------------------------------------------------------------
    // Splitting a shortfall across the units on offer
    // -------------------------------------------------------------------------

    @Test
    fun `a shortfall that divides evenly needs exactly that many units`() {
        val parts = splitShortfall(48, priced("coca", "NGK001", rate = 24).units)
        assertEquals(1, parts.size)
        assertEquals(2, parts.single().qty)
        assertEquals("CASE", parts.single().uomCode)
    }

    @Test
    fun `a partial shortfall rounds up when the case is the only unit sold`() {
        // Rounding down would leave the shelf below the par level the whole
        // exercise exists to restore.
        val units = priced("coca", "NGK001", rate = 24).units
        assertEquals(1, splitShortfall(1, units).single().qty)
        assertEquals(2, splitShortfall(25, units).single().qty)
        assertEquals(3, splitShortfall(49, units).single().qty)
    }

    @Test
    fun `a product sold loose covers the remainder exactly instead of a whole case`() {
        // The reason this split exists. Twenty-six short used to mean two cases and
        // 22 pieces of surplus the customer never agreed to.
        val parts = splitShortfall(26, pricedLoose("coca", "NGK001").units)

        assertEquals(2, parts.size)
        assertEquals("CASE", parts[0].uomCode)
        assertEquals(1, parts[0].qty)
        assertEquals("PCS", parts[1].uomCode)
        assertEquals(2, parts[1].qty)
    }

    @Test
    fun `the biggest unit comes first, so the suggestion reads in picking order`() {
        val parts = splitShortfall(26, pricedLoose("coca", "NGK001").units)
        assertEquals(listOf(24, 1), parts.map { it.conversionRate })
    }

    @Test
    fun `a shortfall smaller than one case is offered as loose pieces alone`() {
        val parts = splitShortfall(2, pricedLoose("coca", "NGK001").units)
        assertEquals(1, parts.size)
        assertEquals("PCS", parts.single().uomCode)
        assertEquals(2, parts.single().qty)
    }

    @Test
    fun `each part carries its own unit price`() {
        // A case and a loose piece are separately priced rows in the price list, and
        // billing the piece at the case price would overcharge the customer.
        val parts = splitShortfall(26, pricedLoose("coca", "NGK001").units)
        assertEquals(228_000L, parts[0].unitPrice)
        assertEquals(10_000L, parts[1].unitPrice)
    }

    @Test
    fun `the middle units take whole ones and only the smallest rounds up`() {
        // Case 24, pack 6, no loose pieces: 26 short is one case and one pack, which
        // overshoots by 4 because nothing smaller is sold. Rounding up on the case
        // instead would have overshot by 22.
        val units = listOf(
            unit("CASE", "Thung", 24, 228_000, isDefault = true),
            unit("PACK", "Loc", 6, 60_000),
        )
        val parts = splitShortfall(26, units)

        assertEquals(listOf(1, 1), parts.map { it.qty })
        assertEquals(listOf("CASE", "PACK"), parts.map { it.uomCode })
        assertEquals(30, parts.sumOf { it.baseQty })
    }

    @Test
    fun `no shortfall suggests nothing`() {
        val units = pricedLoose("coca", "NGK001").units
        assertTrue(splitShortfall(0, units).isEmpty())
        assertTrue(splitShortfall(-24, units).isEmpty())
    }

    @Test
    fun `a corrupt conversion rate is dropped rather than divided by`() {
        // Bad catalogue data must not crash a rep mid-visit, and it must not produce a
        // line either: the server computes base_qty from the same rate, so a line
        // built on a zero would be booked as nothing.
        val units = listOf(
            unit("CASE", "Thung", 0, 228_000, isDefault = true),
            unit("PCS", "Chai", 1, 10_000),
        )
        val parts = splitShortfall(25, units)
        assertEquals(1, parts.size)
        assertEquals("PCS", parts.single().uomCode)
        assertEquals(25, parts.single().qty)
    }

    @Test
    fun `a product with no usable unit produces nothing at all`() {
        val units = listOf(unit("CASE", "Thung", -5, 228_000, isDefault = true))
        assertTrue(splitShortfall(25, units).isEmpty())
    }

    @Test
    fun `two units of the same size do not become two lines`() {
        // Contradictory master data. Offering both would put two lines in front of the
        // customer for one intention.
        val units = listOf(
            unit("CASE", "Thung", 24, 228_000, isDefault = true),
            unit("BOX", "Hop", 24, 230_000),
        )
        val parts = splitShortfall(48, units)
        assertEquals(1, parts.size)
        assertEquals(2, parts.single().qty)
    }

    @Test
    fun `selling in base units means the shortfall is the quantity`() {
        val units = listOf(unit("PCS", "Chai", 1, 10_000, isDefault = true))
        assertEquals(7, splitShortfall(7, units).single().qty)
    }

    // -------------------------------------------------------------------------
    // Assembling suggestions
    // -------------------------------------------------------------------------

    @Test
    fun `a counted SKU below par is suggested in whole sale units`() {
        // Par 48, one case of 24 on the shelf: 24 short, so one more case.
        val suggestions = orderSuggestions(
            mustStock = mapOf("coca" to 48),
            countedBaseQty = mapOf("coca" to 24),
            catalogue = listOf(priced("coca", "NGK001", rate = 24)),
        )

        val suggestion = suggestions.single()
        assertEquals(24, suggestion.shortfallBaseQty)
        assertEquals(1, suggestion.parts.single().qty)
        assertEquals("CASE", suggestion.parts.single().uomCode)
        assertEquals(228_000L, suggestion.parts.single().unitPrice)
        assertEquals(0, suggestion.overshootBaseQty)
    }

    @Test
    fun `the overshoot from rounding is reported rather than buried`() {
        // Two pieces short, but this Coca is sold by the case of 24 alone. Suggesting a
        // whole case is right — a rep should be able to see that is what is happening.
        val suggestion = orderSuggestions(
            mustStock = mapOf("coca" to 48),
            countedBaseQty = mapOf("coca" to 46),
            catalogue = listOf(priced("coca", "NGK001", rate = 24)),
        ).single()

        assertEquals(2, suggestion.shortfallBaseQty)
        assertEquals(1, suggestion.parts.single().qty)
        assertEquals(22, suggestion.overshootBaseQty)
    }

    @Test
    fun `selling the same product loose removes the overshoot entirely`() {
        // Same two-piece gap, same par, but the piece is on the price list.
        val suggestion = orderSuggestions(
            mustStock = mapOf("coca" to 48),
            countedBaseQty = mapOf("coca" to 46),
            catalogue = listOf(pricedLoose("coca", "NGK001")),
        ).single()

        assertEquals(2, suggestion.shortfallBaseQty)
        assertEquals(0, suggestion.overshootBaseQty)
        assertEquals(2, suggestion.suggestedBaseQty)
        assertEquals("PCS", suggestion.parts.single().uomCode)
    }

    @Test
    fun `the picker lands on the biggest part, not the last one written`() {
        val suggestion = orderSuggestions(
            mustStock = mapOf("coca" to 48),
            countedBaseQty = mapOf("coca" to 22),
            catalogue = listOf(pricedLoose("coca", "NGK001")),
        ).single()

        assertEquals("CASE", suggestion.primaryPart?.uomCode)
        assertEquals(26, suggestion.suggestedBaseQty)
    }

    @Test
    fun `a SKU the rep never counted produces no suggestion`() {
        // Nobody looked, so the shelf might be full. Suggesting its full par would
        // put an order in front of a customer on the strength of an omission.
        val suggestions = orderSuggestions(
            mustStock = mapOf("coca" to 48, "pepsi" to 24),
            countedBaseQty = mapOf("coca" to 24),
            catalogue = listOf(
                priced("coca", "NGK001", rate = 24),
                priced("pepsi", "NGK002", rate = 24),
            ),
        )

        assertEquals(listOf("NGK001"), suggestions.map { it.productCode })
    }

    @Test
    fun `a shelf counted at or above par produces no suggestion`() {
        val suggestions = orderSuggestions(
            mustStock = mapOf("coca" to 48, "pepsi" to 24),
            countedBaseQty = mapOf("coca" to 48, "pepsi" to 96),
            catalogue = listOf(
                priced("coca", "NGK001", rate = 24),
                priced("pepsi", "NGK002", rate = 24),
            ),
        )
        assertTrue(suggestions.isEmpty())
    }

    @Test
    fun `an empty shelf is suggested at the full par level`() {
        val suggestion = orderSuggestions(
            mustStock = mapOf("coca" to 48),
            countedBaseQty = mapOf("coca" to 0),
            catalogue = listOf(priced("coca", "NGK001", rate = 24)),
        ).single()

        assertEquals(48, suggestion.shortfallBaseQty)
        assertEquals(2, suggestion.parts.single().qty)
    }

    @Test
    fun `a product missing from the catalogue is skipped rather than offered`() {
        // The server refuses to book an unpriced line, so offering it would only
        // fail after the rep had told the customer it was ordered.
        val suggestions = orderSuggestions(
            mustStock = mapOf("withdrawn" to 48),
            countedBaseQty = mapOf("withdrawn" to 0),
            catalogue = emptyList(),
        )
        assertTrue(suggestions.isEmpty())
    }

    @Test
    fun `suggestions come back in product code order`() {
        // A map has no order of its own, and a list that reshuffles between screen
        // loads is a list a rep cannot trust.
        val suggestions = orderSuggestions(
            mustStock = mapOf("oishi" to 60, "coca" to 48, "pepsi" to 24),
            countedBaseQty = mapOf("oishi" to 0, "coca" to 0, "pepsi" to 0),
            catalogue = listOf(
                priced("oishi", "BK004", rate = 60),
                priced("coca", "NGK001", rate = 24),
                priced("pepsi", "NGK002", rate = 24),
            ),
        )
        assertEquals(listOf("BK004", "NGK001", "NGK002"), suggestions.map { it.productCode })
    }

    @Test
    fun `the seeded KH001 scenario, checked against the live database`() {
        // KH001 is General Trade + Tap hoa, so CORE+GT gives Coca par 48, Aquafina
        // 48, Pepsi 24, plus Khong Do 24, Oreo 36 and Oishi 60.
        //
        // A count submitted through the real function recorded Coca 24, Aquafina 0
        // and Pepsi 96, and left the other three unchecked. Replicating the rule in
        // SQL against that data returned exactly the two figures below, so this test
        // pins the agreement between the domain and the database. The seeded products
        // are sold by the case only, so splitting changes nothing here.
        val suggestions = orderSuggestions(
            mustStock = mapOf(
                "coca" to 48, "aquafina" to 48, "pepsi" to 24,
                "khongdo" to 24, "oreo" to 36, "oishi" to 60,
            ),
            countedBaseQty = mapOf("coca" to 24, "aquafina" to 0, "pepsi" to 96),
            catalogue = listOf(
                priced("coca", "NGK001", rate = 24),
                priced("pepsi", "NGK002", rate = 24),
                priced("aquafina", "NGK003", rate = 24),
                priced("khongdo", "NGK004", rate = 24),
                priced("oreo", "BK001", rate = 36),
                priced("oishi", "BK004", rate = 60),
            ),
        )

        assertEquals(listOf("NGK001", "NGK003"), suggestions.map { it.productCode })
        assertEquals(listOf(1, 2), suggestions.map { it.parts.single().qty })
        assertEquals(listOf(24, 48), suggestions.map { it.shortfallBaseQty })

        // Pepsi is over par, and the three nobody counted are not evidence of an
        // empty shelf — neither becomes an order.
        assertTrue(suggestions.none { it.productCode == "NGK002" })
    }

    @Test
    fun `nothing required means nothing suggested`() {
        assertTrue(
            orderSuggestions(
                mustStock = emptyMap(),
                countedBaseQty = mapOf("coca" to 0),
                catalogue = listOf(priced("coca", "NGK001", rate = 24)),
            ).isEmpty(),
        )
    }
}
