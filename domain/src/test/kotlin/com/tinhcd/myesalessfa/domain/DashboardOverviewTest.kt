package com.tinhcd.myesalessfa.domain

import com.tinhcd.myesalessfa.domain.model.ChartRange
import com.tinhcd.myesalessfa.domain.model.DashboardOverview
import com.tinhcd.myesalessfa.domain.model.MonthFigures
import com.tinhcd.myesalessfa.domain.model.SalesPoint
import com.tinhcd.myesalessfa.domain.model.TodayFigures
import com.tinhcd.myesalessfa.domain.model.heights
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class DashboardOverviewTest {

    @Test
    fun `heights are measured against the tallest day`() {
        val points = listOf(
            SalesPoint("T2", 0),
            SalesPoint("T3", 50),
            SalesPoint("T4", 100),
        )

        assertEquals(listOf(0f, 0.5f, 1f), points.heights())
    }

    @Test
    fun `a span with no sales flattens instead of producing NaN`() {
        // The peak is the divisor, so an empty week divides by zero. NaN does not
        // fail loudly here: it drops the point off the canvas and leaves a chart
        // that still looks like a chart.
        val points = List(7) { SalesPoint("T", 0) }

        val heights = points.heights()

        assertEquals(7, heights.size)
        assertTrue(heights.none { it.isNaN() })
        assertTrue(heights.all { it == 0f })
    }

    @Test
    fun `no points gives no heights`() {
        assertEquals(emptyList<Float>(), emptyList<SalesPoint>().heights())
    }

    @Test
    fun `progress is null when no target was set`() {
        val month = MonthFigures(revenue = 500, revenueTarget = null, orderCount = 5, orderTarget = null)

        assertNull(month.revenueProgress)
        assertNull(month.orderProgress)
    }

    @Test
    fun `a target of zero is not a target`() {
        // Distinct from the null case only in the database; on screen both mean
        // there is nothing to draw a bar against, and dividing by it would give
        // either infinity or NaN.
        val month = MonthFigures(revenue = 500, revenueTarget = 0, orderCount = 5, orderTarget = 0)

        assertNull(month.revenueProgress)
        assertNull(month.orderProgress)
    }

    @Test
    fun `beating the target does not overflow the bar`() {
        val month = MonthFigures(revenue = 300, revenueTarget = 100, orderCount = 9, orderTarget = 3)

        assertEquals(1f, month.revenueProgress)
        assertEquals(1f, month.orderProgress)
    }

    @Test
    fun `progress is the fraction achieved`() {
        val month = MonthFigures(revenue = 25, revenueTarget = 100, orderCount = 1, orderTarget = 4)

        assertEquals(0.25f, month.revenueProgress)
        assertEquals(0.25f, month.orderProgress)
    }

    @Test
    fun `an absent series reads as empty rather than throwing`() {
        // The server sends all three, but a build that only knew two would
        // otherwise crash on the tab the rep tapped.
        val overview = overviewWith(
            mapOf(ChartRange.THIS_WEEK to listOf(SalesPoint("T2", 10))),
        )

        assertEquals(emptyList<SalesPoint>(), overview.series(ChartRange.THIS_MONTH))
        assertEquals(0L, overview.total(ChartRange.THIS_MONTH))
    }

    @Test
    fun `the total is the sum of the selected span`() {
        val overview = overviewWith(
            mapOf(
                ChartRange.THIS_WEEK to listOf(SalesPoint("T2", 10), SalesPoint("T3", 32)),
                ChartRange.LAST_WEEK to listOf(SalesPoint("T2", 5)),
            ),
        )

        assertEquals(42L, overview.total(ChartRange.THIS_WEEK))
        assertEquals(5L, overview.total(ChartRange.LAST_WEEK))
    }

    private fun overviewWith(charts: Map<ChartRange, List<SalesPoint>>) = DashboardOverview(
        date = LocalDate.of(2026, 8, 16),
        today = TodayFigures(0, 0, 0, 0, 0.0),
        month = MonthFigures(0, null, 0, null),
        charts = charts,
    )
}
