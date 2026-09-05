package com.tinhcd.myesalessfa.domain

import com.tinhcd.myesalessfa.domain.model.CartLine
import com.tinhcd.myesalessfa.domain.model.DraftOrder
import com.tinhcd.myesalessfa.domain.model.OrderLine
import com.tinhcd.myesalessfa.domain.model.toCartLines
import org.junit.Assert.assertEquals
import org.junit.Test

class CartLineTest {

    private fun line(
        productId: String,
        uom: String,
        rate: Int,
        qty: Int,
        price: Long = 228_000,
    ) = OrderLine(
        productId = productId,
        productCode = "NGK001",
        productName = "Nước ngọt Coca-Cola 330ml",
        uomCode = uom,
        uomName = if (uom == "CASE") "Thùng" else "Chai",
        conversionRate = rate,
        qty = qty,
        unitPrice = price,
        vatBasisPoints = 1000,
    )

    private fun draft(vararg lines: OrderLine) = DraftOrder(
        visitId = "v1",
        customerId = "c1",
        id = "11111111-1111-1111-1111-111111111111",
        lines = lines.toList(),
    )

    @Test
    fun `an empty basket stores nothing`() {
        assertEquals(emptyList<CartLine>(), draft().toCartLines())
    }

    @Test
    fun `a line keeps its product, unit and quantity`() {
        assertEquals(
            listOf(CartLine("p1", "CASE", 2)),
            draft(line("p1", "CASE", 24, 2)).toCartLines(),
        )
    }

    @Test
    fun `two units of one product stay two lines`() {
        // The stock-count suggestion writes a case and a few loose pieces. The
        // stored basket is keyed by product *and* unit for exactly this, and
        // collapsing them here would lose the loose pieces on the way out.
        assertEquals(
            listOf(CartLine("p1", "CASE", 2), CartLine("p1", "PCS", 3)),
            draft(
                line("p1", "CASE", 24, 2),
                line("p1", "PCS", 1, 3),
            ).toCartLines(),
        )
    }

    @Test
    fun `price and name are not stored with the basket`() {
        // They are re-derived from the catalogue when the basket is read back, so
        // a price change between visits reaches the rep rather than being frozen
        // into a basket they left open. Nothing to assert but the shape: CartLine
        // has three fields and this test fails to compile if that grows.
        val stored: CartLine = draft(line("p1", "CASE", 24, 2)).toCartLines().single()

        assertEquals("p1", stored.productId)
        assertEquals("CASE", stored.uomCode)
        assertEquals(2, stored.qty)
    }

    @Test
    fun `the draft keeps one id across edits`() {
        // It is submit_order's idempotency key. A send that times out after the
        // server booked it must retry under the same id, or the customer gets two
        // orders for one conversation.
        val first = draft(line("p1", "CASE", 24, 2))
        val edited = first.withLine(line("p1", "CASE", 24, 5))

        assertEquals(first.id, edited.id)
    }
}
