package com.tinhcd.myesalessfa.domain.model

import java.time.LocalDate

/**
 * One day's calls, counted the way the trade counts them.
 *
 * A strike is a visit that produced an order. The rest are not failures to be
 * hidden: an outlet found shut or a customer who declined is still a call that
 * was made, and a report showing only strikes would be flattering rather than
 * useful.
 */
data class ActivitySummary(
    /** The day's MCP stops — the denominator, not the visits made. */
    val planned: Int,
    val visited: Int,
    /** Visits to outlets not on today's MCP, including ones the rep registered. */
    val unplanned: Int,
    val strike: Int,
    val nonStrike: Int,
    val closed: Int,
    val orderAmount: Long,
) {
    /**
     * Null rather than zero when nothing was planned.
     *
     * A rep with no MCP stops today has not achieved 0% of anything, and drawing
     * them at zero would be an accusation the data does not support.
     */
    val coverage: Float?
        get() = if (planned <= 0) null else (visited.toFloat() / planned).coerceIn(0f, 1f)

    /** Of the calls made, how many bought. Null when no call was made at all. */
    val strikeRate: Float?
        get() = if (visited <= 0) null else (strike.toFloat() / visited).coerceIn(0f, 1f)
}

data class ActivityRow(
    val visitId: String,
    val customerCode: String,
    val customerName: String,
    val address: String?,
    val planned: Boolean,
    val status: VisitStatus,
    val checkInAtEpochMs: Long?,
    val checkOutAtEpochMs: Long?,
    /** Whole minutes in the shop, or null while the visit is still open. */
    val minutes: Int?,
    val orderAmount: Long,
)

data class ActivityReport(
    val date: LocalDate,
    val summary: ActivitySummary,
    val rows: List<ActivityRow>,
)

data class CustomerSales(
    val customerCode: String,
    val customerName: String,
    val orders: Int,
    val revenue: Long,
)

data class ProductSales(
    val productCode: String,
    val productName: String,
    val baseUom: String,
    val baseQty: Int,
    val revenue: Long,
)

/**
 * One month's money, and the two cuts of it a rep asks for next.
 *
 * Kept as one object because they describe one month: three separate loads could
 * end up describing three, and a total that does not match the sum under it is
 * the fastest way to lose a rep's trust in a report.
 */
data class SalesReport(
    val month: LocalDate,
    val revenue: Long,
    val orderCount: Int,
    /** Null means head office set none, which is not the same as a target of zero. */
    val target: Long?,
    val customers: List<CustomerSales>,
    val products: List<ProductSales>,
) {
    val gap: Long? get() = target?.let { it - revenue }

    val percent: Int?
        get() = target
            ?.takeIf { it > 0 }
            ?.let { Math.round(revenue.toDouble() / it * 100).toInt() }
}
