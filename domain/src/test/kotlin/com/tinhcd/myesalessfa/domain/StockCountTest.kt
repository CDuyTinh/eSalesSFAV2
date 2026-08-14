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
