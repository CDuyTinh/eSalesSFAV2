package com.tinhcd.myesalessfa.domain

import com.tinhcd.myesalessfa.domain.model.PricedProduct
import com.tinhcd.myesalessfa.domain.model.PricedUnit
import com.tinhcd.myesalessfa.domain.model.Product
import com.tinhcd.myesalessfa.domain.model.SaleUnit
import com.tinhcd.myesalessfa.domain.model.orderSuggestions
import com.tinhcd.myesalessfa.domain.model.suggestedSaleQty
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OrderSuggestionTest {

    private fun priced(
        id: String,
        code: String,
        rate: Int,
        price: Long = 228_000,
        uom: String = "CASE",
        uomName: String = "Thung",
    ) = PricedProduct(
        product = Product(id, code, "Product $code", "Nuoc giai khat", "PCS", 1000),
        units = listOf(
            PricedUnit(SaleUnit(uom, uomName, rate, isDefault = true, sortOrder = 1), price),
        ),
    )

    // -------------------------------------------------------------------------
    // Rounding
    // -------------------------------------------------------------------------

    @Test
    fun `a shortfall that divides evenly needs exactly that many units`() {
        assertEquals(2, suggestedSaleQty(shortfallBaseQty = 48, conversionRate = 24))
        assertEquals(1, suggestedSaleQty(shortfallBaseQty = 24, conversionRate = 24))
    }

    @Test
    fun `a partial shortfall rounds up, because half a case cannot be ordered`() {
        // Rounding down would leave the shelf below the par level the whole
        // exercise exists to restore.
        assertEquals(1, suggestedSaleQty(shortfallBaseQty = 1, conversionRate = 24))
        assertEquals(2, suggestedSaleQty(shortfallBaseQty = 25, conversionRate = 24))
        assertEquals(3, suggestedSaleQty(shortfallBaseQty = 49, conversionRate = 24))
    }

    @Test
    fun `no shortfall suggests nothing`() {
        assertEquals(0, suggestedSaleQty(shortfallBaseQty = 0, conversionRate = 24))
        assertEquals(0, suggestedSaleQty(shortfallBaseQty = -24, conversionRate = 24))
    }

    @Test
    fun `a corrupt conversion rate falls back to one rather than dividing by zero`() {
        // Bad catalogue data must not crash a rep mid-visit.
        assertEquals(25, suggestedSaleQty(shortfallBaseQty = 25, conversionRate = 0))
        assertEquals(25, suggestedSaleQty(shortfallBaseQty = 25, conversionRate = -5))
    }

    @Test
    fun `selling in base units means the shortfall is the quantity`() {
        assertEquals(7, suggestedSaleQty(shortfallBaseQty = 7, conversionRate = 1))
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
        assertEquals(1, suggestion.suggestedQty)
        assertEquals("CASE", suggestion.uomCode)
        assertEquals(228_000L, suggestion.unitPrice)
        assertEquals(0, suggestion.overshootBaseQty)
    }

    @Test
    fun `the overshoot from rounding is reported rather than buried`() {
        // Two pieces short, but Coca is sold by the case of 24. Suggesting a whole
        // case is right — a rep should be able to see that is what is happening.
        val suggestion = orderSuggestions(
            mustStock = mapOf("coca" to 48),
            countedBaseQty = mapOf("coca" to 46),
            catalogue = listOf(priced("coca", "NGK001", rate = 24)),
        ).single()

        assertEquals(2, suggestion.shortfallBaseQty)
        assertEquals(1, suggestion.suggestedQty)
        assertEquals(22, suggestion.overshootBaseQty)
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
        assertEquals(2, suggestion.suggestedQty)
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
        // pins the agreement between the domain and the database.
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
        assertEquals(listOf(1, 2), suggestions.map { it.suggestedQty })
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
