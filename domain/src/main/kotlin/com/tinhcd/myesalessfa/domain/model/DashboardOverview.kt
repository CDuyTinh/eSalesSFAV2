package com.tinhcd.myesalessfa.domain.model

import java.time.LocalDate

/** One day on the sell-out line: a short axis label and what was sold. */
data class SalesPoint(
    val title: String,
    val actual: Long,
)

/** The three spans the sell-out chart can show, as the legacy screen had them. */
enum class ChartRange {
    THIS_WEEK,
    LAST_WEEK,
    THIS_MONTH,
}

/**
 * Today, as far as it has got.
 *
 * [visitDone] counts stops the rep actually reached — including outlets found
 * shut and customers who bought nothing. Those are visits that happened, and
 * scoring them as misses would punish a rep for the round they did walk.
 */
data class TodayFigures(
    val revenue: Long,
    val orderCount: Int,
    val visitDone: Int,
    val visitPlanned: Int,
    val skuPerOrder: Double,
)

/**
 * The month so far against what head office asked for.
 *
 * Targets are nullable and that is the point: no target set is not a target of
 * zero. A rep shown "0đ / 0đ" would read it as having been given nothing to do,
 * and a progress bar against zero is either empty or full depending on which way
 * the division falls.
 */
data class MonthFigures(
    val revenue: Long,
    val revenueTarget: Long?,
    val orderCount: Int,
    val orderTarget: Int?,
) {
    /** 0f..1f for a progress bar, or null when there is nothing to progress against. */
    val revenueProgress: Float? = progress(revenue, revenueTarget)

    val orderProgress: Float? = progress(orderCount.toLong(), orderTarget?.toLong())

    private companion object {
        fun progress(actual: Long, target: Long?): Float? {
            if (target == null || target <= 0L) return null
            return (actual.toDouble() / target.toDouble()).coerceIn(0.0, 1.0).toFloat()
        }
    }
}

data class DashboardOverview(
    val date: LocalDate,
    val today: TodayFigures,
    val month: MonthFigures,
    val charts: Map<ChartRange, List<SalesPoint>>,
) {
    fun series(range: ChartRange): List<SalesPoint> = charts[range].orEmpty()

    /** What the chart's total line shows for the selected span. */
    fun total(range: ChartRange): Long = series(range).sumOf { it.actual }
}

/**
 * Point heights as a fraction of the tallest day, for drawing.
 *
 * Kept here rather than in the Composable so the awkward case has a test rather
 * than a coincidence behind it: a span with no sales at all divides by zero, and
 * a NaN coordinate does not fail loudly — it silently drops the point off the
 * canvas, leaving a chart that looks plausible and is wrong. A flat line along
 * the bottom is the honest picture of a week with no orders.
 */
fun List<SalesPoint>.heights(): List<Float> {
    val peak = maxOfOrNull { it.actual } ?: 0L
    if (peak <= 0L) return List(size) { 0f }
    return map { (it.actual.toDouble() / peak.toDouble()).toFloat() }
}
