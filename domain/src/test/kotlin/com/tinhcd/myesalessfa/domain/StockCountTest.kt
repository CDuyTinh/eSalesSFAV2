package com.tinhcd.myesalessfa.domain

import com.tinhcd.myesalessfa.domain.model.DraftStockCount
import com.tinhcd.myesalessfa.domain.model.StockCountLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The figures here are the ones `submit_stock_count` computes server-side, so
 * these tests double as a statement of what that function must produce.
 */
class StockCountTest {

    private fun line(
        qty: Int,
        prevBaseQty: Int = 0,
        conversionRate: Int = 24,
        productId: String = "coca",
        uomCode: String = "CASE",
    ) = StockCountLine(
        productId = productId,
        productCode = "NGK001",
        productName = "Coca-Cola 330ml",
        uomCode = uomCode,
        uomName = "Thung",
        conversionRate = conversionRate,
        qty = qty,
        prevBaseQty = prevBaseQty,
    )

    @Test
    fun `the count converts to base units before anything is compared`() {
        // 5 cases of 24 last visit against 2 this visit: 120 down to 48, so 72
        // pieces left the shelf. Comparing 5 to 2 would say 3 of something.
        val l = line(qty = 2, prevBaseQty = 120, conversionRate = 24)
        assertEquals(48, l.baseQty)
        assertEquals(72, l.soldSinceCount)
    }

    @Test
    fun `a product counted in a different unit than last time still compares`() {
        // Counted loose this week, by the case last week. Base units are the only
        // footing on which those two numbers mean anything together.
        val l = line(qty = 30, prevBaseQty = 120, conversionRate = 1, uomCode = "PCS")
        assertEquals(30, l.baseQty)
        assertEquals(90, l.soldSinceCount)
    }

    @Test
    fun `zero is a real count and reports as out of stock`() {
        val l = line(qty = 0, prevBaseQty = 3, conversionRate = 1)
        assertEquals(0, l.baseQty)
        assertEquals(3, l.soldSinceCount)
        assertTrue(l.isNewlyOutOfStock)
    }

    @Test
    fun `a product that was already empty is not newly out of stock`() {
        // Nothing changed since last visit, so there is nothing to flag.
        assertFalse(line(qty = 0, prevBaseQty = 0).isNewlyOutOfStock)
    }

    @Test
    fun `a product with no history is simply new to the outlet`() {
        val l = line(qty = 4, prevBaseQty = 0)
        assertEquals(96, l.baseQty)
        assertEquals(0, l.soldSinceCount)
        assertFalse(l.isNewlyOutOfStock)
    }

    @Test
    fun `restocking from elsewhere does not report as negative sales`() {
        // The outlet bought from another source between visits. Sales of -24
        // would read as the rep having miscounted.
        val l = line(qty = 6, prevBaseQty = 120, conversionRate = 24)
        assertEquals(144, l.baseQty)
        assertEquals(0, l.soldSinceCount)
    }

    @Test
    fun `recording the same product and unit twice corrects it rather than stacking`() {
        val count = DraftStockCount(visitId = "v1", customerId = "c1")
            .withLine(line(qty = 5))
            .withLine(line(qty = 2))

        assertEquals(1, count.lines.size)
        assertEquals(2, count.lines.single().qty)
    }

    @Test
    fun `not checking a product differs from checking it and finding none`() {
        // Removing the line says the rep never looked; a zero says they did. Head
        // office can act on the second and not on the first.
        val checked = DraftStockCount(visitId = "v1", customerId = "c1")
            .withLine(line(qty = 0, prevBaseQty = 24))
        assertEquals(1, checked.countedProducts)
        assertEquals(1, checked.outOfStockCount)

        val unchecked = checked.withoutLine("coca", "CASE")
        assertEquals(0, unchecked.countedProducts)
        assertNull(unchecked.lineFor("coca", "CASE"))
    }

    @Test
    fun `an empty count cannot be submitted but an all-zero count can`() {
        val empty = DraftStockCount(visitId = "v1", customerId = "c1")
        assertFalse(empty.canSubmit)

        // An outlet that has sold out of everything checked is precisely the
        // report worth sending.
        val soldOut = empty
            .withLine(line(qty = 0, prevBaseQty = 24, productId = "coca"))
            .withLine(line(qty = 0, prevBaseQty = 12, productId = "pepsi"))
        assertTrue(soldOut.canSubmit)
        assertEquals(2, soldOut.outOfStockCount)
    }

    // -------------------------------------------------------------------------
    // Must-stock list
    // -------------------------------------------------------------------------

    @Test
    fun `the shortfall against par is the replenishment figure`() {
        // Par 48 base units, 2 cases of 24 on the shelf: nothing to replenish.
        val met = line(qty = 2, conversionRate = 24).copy(mslMinBaseQty = 48)
        assertEquals(0, met.shortfallBaseQty)
        assertTrue(met.isMustStock)

        // One case left against a par of 48: 24 short.
        val short = line(qty = 1, conversionRate = 24).copy(mslMinBaseQty = 48)
        assertEquals(24, short.shortfallBaseQty)

        // Empty shelf: the whole par is the shortfall.
        assertEquals(48, line(qty = 0, conversionRate = 24).copy(mslMinBaseQty = 48).shortfallBaseQty)
    }

    @Test
    fun `a par level is a floor, not a target to top up to exactly`() {
        // Four cases against a par of 48 is over, not short. Suggesting an order
        // anyway would train the rep to ignore the number.
        val over = line(qty = 4, conversionRate = 24).copy(mslMinBaseQty = 48)
        assertEquals(96, over.baseQty)
        assertEquals(0, over.shortfallBaseQty)
    }

    @Test
    fun `a product off the list has no par and no shortfall`() {
        val ordinary = line(qty = 0, conversionRate = 24)
        assertFalse(ordinary.isMustStock)
        assertEquals(0, ordinary.shortfallBaseQty)
    }

    @Test
    fun `compliance counts available, out of stock and unchecked separately`() {
        // Three required SKUs. One found, one counted empty, one never looked at.
        val count = DraftStockCount(
            visitId = "v1",
            customerId = "c1",
            mustStock = mapOf("coca" to 48, "pepsi" to 24, "oreo" to 36),
        )
            .withLine(line(qty = 2, productId = "coca", conversionRate = 24).copy(mslMinBaseQty = 48))
            .withLine(line(qty = 0, productId = "pepsi", conversionRate = 24).copy(mslMinBaseQty = 24))

        val compliance = count.compliance
        assertEquals(3, compliance.required)
        assertEquals(1, compliance.available)
        assertEquals(1, compliance.outOfStock)
        assertEquals(1, compliance.unchecked)
        assertFalse(compliance.isComplete)
        assertEquals(setOf("oreo"), count.uncheckedMustStock)
    }

    @Test
    fun `availability is measured over what was checked, not over what was required`() {
        // One of two checked SKUs was on the shelf, so 50% — the third, unlooked-at
        // SKU is not evidence either way and reporting it as absent would put the
        // rep's omission on the outlet's record.
        val count = DraftStockCount(
            visitId = "v1",
            customerId = "c1",
            mustStock = mapOf("coca" to 48, "pepsi" to 24, "oreo" to 36),
        )
            .withLine(line(qty = 2, productId = "coca").copy(mslMinBaseQty = 48))
            .withLine(line(qty = 0, productId = "pepsi").copy(mslMinBaseQty = 24))

        assertEquals(50, count.compliance.availabilityPercent)
    }

    @Test
    fun `a count with nothing checked reports zero availability rather than dividing by zero`() {
        val count = DraftStockCount(
            visitId = "v1",
            customerId = "c1",
            mustStock = mapOf("coca" to 48),
        )
        assertEquals(0, count.compliance.availabilityPercent)
        assertEquals(1, count.compliance.unchecked)
    }

    @Test
    fun `a non-required product does not count towards compliance`() {
        // Counting something off the list is useful, but it cannot make the outlet
        // look compliant.
        val count = DraftStockCount(
            visitId = "v1",
            customerId = "c1",
            mustStock = mapOf("coca" to 48),
        ).withLine(line(qty = 5, productId = "sunlight"))

        assertEquals(1, count.compliance.required)
        assertEquals(0, count.compliance.available)
        assertEquals(1, count.compliance.unchecked)
    }

    @Test
    fun `the total shortfall sums only what is genuinely short`() {
        val count = DraftStockCount(
            visitId = "v1",
            customerId = "c1",
            mustStock = mapOf("coca" to 48, "pepsi" to 24, "oreo" to 36),
        )
            .withLine(line(qty = 1, productId = "coca", conversionRate = 24).copy(mslMinBaseQty = 48))
            .withLine(line(qty = 0, productId = "pepsi", conversionRate = 24).copy(mslMinBaseQty = 24))
            .withLine(line(qty = 9, productId = "oreo", conversionRate = 36).copy(mslMinBaseQty = 36))

        // 24 short + 24 short + 0 (324 on the shelf against a par of 36)
        assertEquals(48, count.totalShortfallBaseQty)
    }

    @Test
    fun `the same product counted in two units is two lines`() {
        // 2 full cases out back and 7 loose on the shelf is a normal count.
        val count = DraftStockCount(visitId = "v1", customerId = "c1")
            .withLine(line(qty = 2, conversionRate = 24, uomCode = "CASE"))
            .withLine(line(qty = 7, conversionRate = 1, uomCode = "PCS"))

        assertEquals(2, count.lines.size)
        assertEquals(55, count.lines.sumOf { it.baseQty })
    }
}
