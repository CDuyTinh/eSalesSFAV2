package com.tinhcd.myesalessfa.domain

import com.tinhcd.myesalessfa.domain.model.CustomerOrder
import com.tinhcd.myesalessfa.domain.model.CustomerOrderLine
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class CustomerOrderTest {

    private fun line(code: String, uom: String, qty: Int) =
        CustomerOrderLine(code, "Product $code", uom, qty, qty * 10_000L)

    private fun order(vararg lines: CustomerOrderLine) = CustomerOrder(
        orderId = "o1",
        orderNo = "DH001",
        orderDate = LocalDate.of(2026, 8, 15),
        status = "new",
        totalAmount = lines.sumOf { it.lineAmount },
        lines = lines.toList(),
    )

    /**
     * The case this was got wrong on first: an order carrying one product as a
     * case and as loose pieces was reported to the rep as two items. The order
     * keys a line by product *and* unit, so two lines is normal and says nothing
     * about how many things are on the shelf.
     */
    @Test
    fun countsOneProductInTwoUnitsAsOneItem() {
        val subject = order(
            line("NGK003", "CASE", 1),
            line("NGK003", "PCS", 2),
        )

        assertEquals(1, subject.skuCount)
    }

    @Test
    fun countsDistinctProductsSeparately() {
        val subject = order(
            line("NGK001", "CASE", 4),
            line("NGK003", "CASE", 1),
            line("NGK003", "PCS", 2),
        )

        assertEquals(2, subject.skuCount)
    }

    /** Quantity is what the rep handed over, so units are summed as written. */
    @Test
    fun sumsQuantityAcrossUnits() {
        val subject = order(
            line("NGK003", "CASE", 1),
            line("NGK003", "PCS", 2),
        )

        assertEquals(3, subject.totalQty)
    }
}
