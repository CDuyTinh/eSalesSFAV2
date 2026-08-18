package com.tinhcd.myesalessfa.domain

import com.tinhcd.myesalessfa.domain.model.FocusProduct
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class FocusProductTest {

    private fun focus(
        target: Int? = 500,
        sold: Int = 0,
        to: LocalDate = LocalDate.of(2026, 8, 31),
    ) = FocusProduct(
        focusId = "f1",
        productId = "p1",
        productCode = "NGK001",
        productName = "Coca-Cola 330ml",
        baseUom = "PCS",
        fromDate = LocalDate.of(2026, 8, 1),
        toDate = to,
        priority = 1,
        targetBaseQty = target,
        note = null,
        soldBaseQty = sold,
        outlets = 3,
    )

    @Test
    fun `progress is the fraction of the target sold`() {
        assertEquals(0.5f, focus(target = 500, sold = 250).progress)
        assertEquals(50, focus(target = 500, sold = 250).percent)
    }

    @Test
    fun `a qualitative push has no progress to show`() {
        // "Get it on the shelf" is a real instruction. Treating it as a target of
        // zero would either divide by zero or draw the rep at 100% for selling
        // nothing.
        assertNull(focus(target = null, sold = 40).progress)
        assertNull(focus(target = null, sold = 40).percent)
        assertNull(focus(target = null).remaining())
    }

    @Test
    fun `beating the target fills the bar but keeps the real number`() {
        assertEquals(1f, focus(target = 500, sold = 750).progress)
        assertEquals(150, focus(target = 500, sold = 750).percent)
    }

    @Test
    fun `what is left never goes negative`() {
        assertEquals(250, focus(target = 500, sold = 250).remaining())
        assertEquals(0, focus(target = 500, sold = 750).remaining())
    }

    @Test
    fun `the last day of a push still counts as a day`() {
        // A rep has the whole of the closing day to sell in, so it must not read
        // as zero days left.
        val today = LocalDate.of(2026, 8, 31)

        assertEquals(1L, focus(to = today).daysLeft(today))
    }

    @Test
    fun `a finished push has no days left rather than negative ones`() {
        val today = LocalDate.of(2026, 9, 5)

        assertEquals(0L, focus(to = LocalDate.of(2026, 8, 31)).daysLeft(today))
    }

    @Test
    fun `ending soon is the last three days and not before`() {
        val to = LocalDate.of(2026, 8, 31)

        assertTrue(focus(to = to).isEndingSoon(LocalDate.of(2026, 8, 29)))
        assertTrue(focus(to = to).isEndingSoon(LocalDate.of(2026, 8, 31)))
        assertFalse(focus(to = to).isEndingSoon(LocalDate.of(2026, 8, 28)))
    }

    @Test
    fun `a push that has already ended is not ending soon`() {
        // Otherwise an expired row would keep shouting at the rep in amber.
        assertFalse(focus(to = LocalDate.of(2026, 8, 31)).isEndingSoon(LocalDate.of(2026, 9, 1)))
    }
}
