package com.tinhcd.myesalessfa.domain

import com.tinhcd.myesalessfa.domain.model.DraftOrder
import com.tinhcd.myesalessfa.domain.model.OrderLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The server recomputes all of this in `submit_order`. Every expected figure
 * below is therefore also an assertion about that function: if one side changes
 * its rounding, these numbers are what catches it.
 *
 * Amounts are asserted with explicit `L` literals. `assertEquals(1235, aLong)`
 * can bind to assertEquals(Object, Object), which boxes an Int against a Long
 * and fails for a reason that has nothing to do with the money.
 */
class OrderMoneyTest {

    private fun line(
        qty: Int,
        unitPrice: Long,
        vatBasisPoints: Int = 1000,
        conversionRate: Int = 24,
        productId: String = "p1",
        uomCode: String = "CASE",
    ) = OrderLine(
        productId = productId,
        productCode = "NGK001",
        productName = "Coca-Cola 330ml",
        uomCode = uomCode,
        uomName = "Thung",
        conversionRate = conversionRate,
        qty = qty,
        unitPrice = unitPrice,
        vatBasisPoints = vatBasisPoints,
    )

    @Test
    fun `a line totals qty times price plus VAT`() {
        val l = line(qty = 2, unitPrice = 222_000, vatBasisPoints = 1000)
        assertEquals(444_000L, l.grossAmount)
        assertEquals(44_400L, l.vatAmount)
        assertEquals(488_400L, l.lineAmount)
    }

    @Test
    fun `VAT rounds half up, not half to even`() {
        // gross 12345 at 10% is exactly 1234.5. Half-up gives 1235; half-to-even
        // gives 1234. The server's integer (gross * bp + 5000) / 10000 gives
        // 1235, so this is the tie-break both sides have to share.
        assertEquals(1235L, line(qty = 1, unitPrice = 12_345).vatAmount)

        // The next tie up, to show it is the rule and not one lucky number:
        // 1235.5 rounds to 1236, where half-to-even would also give 1236, and
        // 1234.5 -> 1235 above is where the two disagree.
        assertEquals(1236L, line(qty = 1, unitPrice = 12_355).vatAmount)
    }

    @Test
    fun `a zero-rated line adds no VAT`() {
        val l = line(qty = 3, unitPrice = 50_000, vatBasisPoints = 0)
        assertEquals(150_000L, l.grossAmount)
        assertEquals(0L, l.vatAmount)
        assertEquals(150_000L, l.lineAmount)
    }

    @Test
    fun `base quantity multiplies through the unit conversion`() {
        // 3 cases of 100 is 300 pieces. This is the number stock and reporting
        // use, and taking it from the wrong side of the conversion is the classic
        // version of this bug.
        assertEquals(300, line(qty = 3, unitPrice = 1_130_000, conversionRate = 100).baseQty)

        // The base unit's own row is rate 1, so it needs no special case.
        assertEquals(7, line(qty = 7, unitPrice = 12_000, conversionRate = 1).baseQty)
    }

    @Test
    fun `order VAT is summed per line because the catalogue mixes rates`() {
        // 2 cases of Coca at 10% and 1 case of Aquafina at 8%. Applying either
        // rate to the subtotal would be wrong for the other line.
        val order = DraftOrder(visitId = "v1", customerId = "c1", id = "o1")
            .withLine(line(qty = 2, unitPrice = 222_000, vatBasisPoints = 1000, productId = "coca"))
            .withLine(line(qty = 1, unitPrice = 112_000, vatBasisPoints = 800, productId = "aqua"))

        assertEquals(556_000L, order.subTotal)
        assertEquals(53_360L, order.vatAmount) // 44_400 + 8_960
        assertEquals(609_360L, order.totalAmount)
    }

    @Test
    fun `the order total equals the sum of its line totals`() {
        // subTotal + vatAmount and the sum of lineAmount are two routes to the
        // same figure; the rep reads one out and the ERP reconciles the other.
        val order = DraftOrder(visitId = "v1", customerId = "c1", id = "o1")
            .withLine(line(qty = 4, unitPrice = 12_345, vatBasisPoints = 1000, productId = "a"))
            .withLine(line(qty = 3, unitPrice = 5_555, vatBasisPoints = 800, productId = "b"))
            .withLine(line(qty = 1, unitPrice = 690_000, vatBasisPoints = 1000, productId = "c"))

        assertEquals(order.lines.sumOf { it.lineAmount }, order.totalAmount)
        assertEquals(order.subTotal + order.vatAmount, order.totalAmount)
    }

    @Test
    fun `an empty order cannot be submitted`() {
        val empty = DraftOrder(visitId = "v1", customerId = "c1", id = "o1")
        assertFalse(empty.canSubmit)
        assertEquals(0L, empty.totalAmount)
        assertTrue(empty.withLine(line(qty = 1, unitPrice = 1_000)).canSubmit)
    }

    @Test
    fun `re-adding the same product and unit replaces the line instead of stacking`() {
        // The rep tapping a product twice must not double the order, and the
        // server's unique (order, product, unit) would reject it anyway.
        val order = DraftOrder(visitId = "v1", customerId = "c1", id = "o1")
            .withLine(line(qty = 2, unitPrice = 222_000))
            .withLine(line(qty = 5, unitPrice = 222_000))

        assertEquals(1, order.lines.size)
        assertEquals(5, order.lines.single().qty)
        assertEquals(1_110_000L, order.subTotal)
    }

    @Test
    fun `the same product in two different units is two lines`() {
        // 2 cases plus 3 loose is a real order, not a mistake.
        val order = DraftOrder(visitId = "v1", customerId = "c1", id = "o1")
            .withLine(line(qty = 2, unitPrice = 222_000, uomCode = "CASE", conversionRate = 24))
            .withLine(line(qty = 3, unitPrice = 10_000, uomCode = "PCS", conversionRate = 1))

        assertEquals(2, order.lines.size)
        assertEquals(474_000L, order.subTotal)
        assertEquals(51, order.lines.sumOf { it.baseQty }) // 48 + 3
    }

    @Test
    fun `removing a line leaves the others priced as they were`() {
        val order = DraftOrder(visitId = "v1", customerId = "c1", id = "o1")
            .withLine(line(qty = 2, unitPrice = 222_000, productId = "coca"))
            .withLine(line(qty = 1, unitPrice = 112_000, productId = "aqua"))
            .withoutLine("coca", "CASE")

        assertEquals(1, order.lines.size)
        assertEquals(112_000L, order.subTotal)
        assertEquals(0, order.quantityOf("coca", "CASE"))
        assertEquals(1, order.quantityOf("aqua", "CASE"))
    }
}
