package com.tinhcd.myesalessfa.domain.model

import java.time.LocalDate

/**
 * The four reports the rebuild was missing, beside the two it had.
 *
 * All four answer the same question over a window — what did this rep do
 * between these two dates — which is why they share [ReportWindow] rather than
 * each carrying its own pair of dates.
 */
data class ReportWindow(
    val fromDate: LocalDate,
    val toDate: LocalDate,
) {
    /** Sane by construction: a window that ends before it starts shows nothing. */
    val isValid: Boolean get() = !toDate.isBefore(fromDate)

    companion object {
        /** The default every report opens on: this month so far. */
        fun thisMonth(today: LocalDate = LocalDate.now()): ReportWindow =
            ReportWindow(today.withDayOfMonth(1), today)

        fun lastDays(days: Long, today: LocalDate = LocalDate.now()): ReportWindow =
            ReportWindow(today.minusDays(days), today)
    }
}

// -----------------------------------------------------------------------------
// Báo cáo đơn hàng
// -----------------------------------------------------------------------------

enum class OrderReportStatus(val wire: String, val label: String) {
    NEW("new", "Chờ xử lý"),
    CONFIRMED("confirmed", "Đã xác nhận"),
    CANCELLED("cancelled", "Đã hủy");

    companion object {
        fun fromWire(value: String): OrderReportStatus =
            entries.firstOrNull { it.wire == value } ?: NEW
    }
}

data class OrderReportRow(
    val id: String,
    val orderNo: String,
    val orderDate: String,
    val customerCode: String,
    val customerName: String,
    val status: OrderReportStatus,
    /** Booked inside a visit, rather than off-route. */
    val inRoute: Boolean,
    val lineCount: Int,
    val subTotal: Long,
    val discountAmount: Long,
    val vatAmount: Long,
    val totalAmount: Long,
)

data class OrderReport(
    val total: Int = 0,
    /** Cancelled orders are counted but not summed — the money never moved. */
    val totalAmount: Long = 0,
    val counts: Map<OrderReportStatus, Int> = emptyMap(),
    val items: List<OrderReportRow> = emptyList(),
) {
    fun count(status: OrderReportStatus): Int = counts[status] ?: 0

    val presentStatuses: List<OrderReportStatus>
        get() = OrderReportStatus.entries.filter { count(it) > 0 }
}

// -----------------------------------------------------------------------------
// Báo cáo SKU
// -----------------------------------------------------------------------------

/**
 * One product's quantity over the window, in base units.
 *
 * Split the way the legacy splits it: a rep is measured on what was booked and
 * paid on what was approved, so both figures are shown rather than one.
 */
data class SkuReportRow(
    val productId: String,
    val productCode: String,
    val productName: String,
    val stockUom: String,
    val qtyConfirmed: Int,
    val qtyPending: Int,
    val qtyTotal: Int,
    val amountTotal: Long,
) {
    /** How much of what was booked has actually been approved. */
    val confirmedShare: Float?
        get() = if (qtyTotal <= 0) null else (qtyConfirmed.toFloat() / qtyTotal).coerceIn(0f, 1f)
}

data class SkuReport(
    val items: List<SkuReportRow> = emptyList(),
) {
    val qtyConfirmed: Int get() = items.sumOf { it.qtyConfirmed }
    val qtyPending: Int get() = items.sumOf { it.qtyPending }
    val qtyTotal: Int get() = items.sumOf { it.qtyTotal }
    val amountTotal: Long get() = items.sumOf { it.amountTotal }
    val skuCount: Int get() = items.size
}

// -----------------------------------------------------------------------------
// Báo cáo thương mại
// -----------------------------------------------------------------------------

enum class TradeReportKind(val wire: String, val label: String) {
    DISPLAY("display", "Trưng bày"),
    LOYALTY("loyalty", "Tích lũy");

    companion object {
        fun fromWire(value: String): TradeReportKind =
            entries.firstOrNull { it.wire == value } ?: DISPLAY
    }
}

data class TradeReportRow(
    val kind: TradeReportKind,
    val registeredAt: String,
    val status: String,
    val portion: Int,
    val customerCode: String,
    val customerName: String,
    val programCode: String,
    val programName: String,
    val levelName: String?,
)

data class TradeReport(
    val displayCount: Int = 0,
    val loyaltyCount: Int = 0,
    /** Outlets reached, not sign-ups: one shop in three programmes is one shop. */
    val customerCount: Int = 0,
    val items: List<TradeReportRow> = emptyList(),
) {
    fun of(kind: TradeReportKind?): List<TradeReportRow> =
        if (kind == null) items else items.filter { it.kind == kind }

    val total: Int get() = displayCount + loyaltyCount
}

// -----------------------------------------------------------------------------
// Báo cáo kết quả công việc
// -----------------------------------------------------------------------------

data class WorkResultRow(
    val id: String,
    val content: String,
    val categoryName: String,
    val status: WorkPlanStatus,
    val fromAt: String,
    val toAt: String,
    val objectCount: Int,
    /** What came of it, whichever way it went: the result or the reason. */
    val outcome: String?,
)

data class WorkResultReport(
    val completed: Int = 0,
    val cancelled: Int = 0,
    val total: Int = 0,
    val items: List<WorkResultRow> = emptyList(),
) {
    /** How many of the finished plans actually got done. */
    val completionRate: Float?
        get() = if (total <= 0) null else (completed.toFloat() / total).coerceIn(0f, 1f)
}
