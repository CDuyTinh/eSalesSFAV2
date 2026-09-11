package com.tinhcd.myesalessfa.domain

import com.tinhcd.myesalessfa.domain.model.OrderReport
import com.tinhcd.myesalessfa.domain.model.OrderReportStatus
import com.tinhcd.myesalessfa.domain.model.ReportWindow
import com.tinhcd.myesalessfa.domain.model.SkuReport
import com.tinhcd.myesalessfa.domain.model.SkuReportRow
import com.tinhcd.myesalessfa.domain.model.TradeReport
import com.tinhcd.myesalessfa.domain.model.TradeReportKind
import com.tinhcd.myesalessfa.domain.model.TradeReportRow
import com.tinhcd.myesalessfa.domain.model.WorkResultReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ReportExtrasTest {

    private val today = LocalDate.of(2026, 9, 11)

    // -------------------------------------------------------------------------
    // The shared window
    // -------------------------------------------------------------------------

    @Test
    fun `this month runs from the first to today`() {
        val w = ReportWindow.thisMonth(today)
        assertEquals(LocalDate.of(2026, 9, 1), w.fromDate)
        assertEquals(today, w.toDate)
        assertTrue(w.isValid)
    }

    @Test
    fun `a window that ends before it starts is not valid`() {
        assertFalse(ReportWindow(today, today.minusDays(1)).isValid)
    }

    /** A single day is a window, not an error — a rep asking about today. */
    @Test
    fun `a window of one day is valid`() {
        assertTrue(ReportWindow(today, today).isValid)
    }

    // -------------------------------------------------------------------------
    // Đơn hàng
    // -------------------------------------------------------------------------

    @Test
    fun `only statuses that occur get a chip`() {
        val report = OrderReport(
            total = 5,
            counts = mapOf(
                OrderReportStatus.NEW to 3,
                OrderReportStatus.CONFIRMED to 2,
            ),
        )

        assertEquals(
            listOf(OrderReportStatus.NEW, OrderReportStatus.CONFIRMED),
            report.presentStatuses,
        )
        assertEquals(0, report.count(OrderReportStatus.CANCELLED))
    }

    @Test
    fun `chips keep enum order whatever order the server sent`() {
        val report = OrderReport(
            counts = mapOf(
                OrderReportStatus.CANCELLED to 1,
                OrderReportStatus.NEW to 1,
            ),
        )

        assertEquals(
            listOf(OrderReportStatus.NEW, OrderReportStatus.CANCELLED),
            report.presentStatuses,
        )
    }

    @Test
    fun `an unknown order status falls back rather than throwing`() {
        assertEquals(OrderReportStatus.NEW, OrderReportStatus.fromWire("shipped"))
        assertEquals(OrderReportStatus.CANCELLED, OrderReportStatus.fromWire("cancelled"))
    }

    // -------------------------------------------------------------------------
    // SKU
    // -------------------------------------------------------------------------

    private fun sku(code: String, confirmed: Int, pending: Int, amount: Long = 0) =
        SkuReportRow(
            productId = code,
            productCode = code,
            productName = code,
            stockUom = "PCS",
            qtyConfirmed = confirmed,
            qtyPending = pending,
            qtyTotal = confirmed + pending,
            amountTotal = amount,
        )

    @Test
    fun `sku totals add the rows up`() {
        val report = SkuReport(
            items = listOf(
                sku("A", confirmed = 10, pending = 5, amount = 1_000),
                sku("B", confirmed = 0, pending = 20, amount = 2_000),
            ),
        )

        assertEquals(10, report.qtyConfirmed)
        assertEquals(25, report.qtyPending)
        assertEquals(35, report.qtyTotal)
        assertEquals(3_000L, report.amountTotal)
        assertEquals(2, report.skuCount)
    }

    @Test
    fun `an empty sku report totals zero rather than dividing by it`() {
        val report = SkuReport()
        assertEquals(0, report.qtyTotal)
        assertEquals(0, report.skuCount)
    }

    @Test
    fun `confirmed share is a fraction of what was booked`() {
        assertEquals(0.5f, sku("A", confirmed = 10, pending = 10).confirmedShare!!, 0.001f)
    }

    /** Nothing booked is not nought per cent approved — it is no answer at all. */
    @Test
    fun `confirmed share is null when nothing was booked`() {
        assertNull(sku("A", confirmed = 0, pending = 0).confirmedShare)
    }

    // -------------------------------------------------------------------------
    // Thương mại
    // -------------------------------------------------------------------------

    private fun trade(kind: TradeReportKind, customer: String, program: String) =
        TradeReportRow(
            kind = kind,
            registeredAt = "2026-09-01",
            status = "pending",
            portion = 1,
            customerCode = customer,
            customerName = customer,
            programCode = program,
            programName = program,
            levelName = null,
        )

    @Test
    fun `the trade filter narrows to one kind`() {
        val report = TradeReport(
            displayCount = 1,
            loyaltyCount = 1,
            customerCount = 1,
            items = listOf(
                trade(TradeReportKind.DISPLAY, "KH001", "TB1"),
                trade(TradeReportKind.LOYALTY, "KH001", "TL1"),
            ),
        )

        assertEquals(2, report.of(null).size)
        assertEquals(1, report.of(TradeReportKind.LOYALTY).size)
        assertEquals(2, report.total)
    }

    /**
     * Outlets reached is not sign-ups made, and the report carries both because
     * one shop in three programmes reads very differently from three shops.
     */
    @Test
    fun `customer count is independent of the sign-up count`() {
        val report = TradeReport(displayCount = 2, loyaltyCount = 1, customerCount = 1)
        assertEquals(3, report.total)
        assertEquals(1, report.customerCount)
    }

    @Test
    fun `an unknown trade kind falls back rather than throwing`() {
        assertEquals(TradeReportKind.DISPLAY, TradeReportKind.fromWire("posm"))
        assertEquals(TradeReportKind.LOYALTY, TradeReportKind.fromWire("loyalty"))
    }

    // -------------------------------------------------------------------------
    // Kết quả công việc
    // -------------------------------------------------------------------------

    @Test
    fun `completion rate is of the plans that finished`() {
        val report = WorkResultReport(completed = 3, cancelled = 1, total = 4)
        assertEquals(0.75f, report.completionRate!!, 0.001f)
    }

    @Test
    fun `completion rate is null when nothing finished`() {
        assertNull(WorkResultReport().completionRate)
    }

    @Test
    fun `completion rate never exceeds one`() {
        // A total that disagrees with the parts is the server's business, but
        // the bar must not run off the end of the card because of it.
        val report = WorkResultReport(completed = 5, cancelled = 0, total = 4)
        assertEquals(1f, report.completionRate!!, 0.001f)
    }
}
