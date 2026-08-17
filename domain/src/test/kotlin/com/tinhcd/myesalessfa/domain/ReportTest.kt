package com.tinhcd.myesalessfa.domain

import com.tinhcd.myesalessfa.domain.model.ActivitySummary
import com.tinhcd.myesalessfa.domain.model.SalesReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class ReportTest {

    private fun summary(
        planned: Int = 10,
        visited: Int = 8,
        strike: Int = 5,
    ) = ActivitySummary(
        planned = planned,
        visited = visited,
        unplanned = 0,
        strike = strike,
        nonStrike = visited - strike,
        closed = 0,
        orderAmount = 0,
    )

    @Test
    fun `coverage is visits over the plan`() {
        assertEquals(0.8f, summary(planned = 10, visited = 8).coverage)
    }

    @Test
    fun `no plan is not zero coverage`() {
        // A rep with no MCP stops today has not achieved 0% of anything, and
        // drawing them at zero would be an accusation the data cannot support.
        assertNull(summary(planned = 0, visited = 3).coverage)
    }

    @Test
    fun `strike rate is of calls made, not of calls planned`() {
        // The rep is judged on what they did with the doors they got through,
        // not on the ones they never reached.
        assertEquals(0.625f, summary(planned = 10, visited = 8, strike = 5).strikeRate)
    }

    @Test
    fun `no calls made means no strike rate`() {
        assertNull(summary(visited = 0, strike = 0).strikeRate)
    }

    @Test
    fun `beating the plan does not exceed full coverage`() {
        // Off-route calls can push visits past the plan. The bar stops at full;
        // the counts underneath still say 12 of 10.
        assertEquals(1f, summary(planned = 10, visited = 12).coverage)
    }

    // -------------------------------------------------------------------------
    // Sales
    // -------------------------------------------------------------------------

    private fun report(revenue: Long, target: Long?) = SalesReport(
        month = LocalDate.of(2026, 8, 1),
        revenue = revenue,
        orderCount = 3,
        target = target,
        customers = emptyList(),
        products = emptyList(),
    )

    @Test
    fun `the gap runs both ways`() {
        assertEquals(20L, report(revenue = 80, target = 100).gap)
        assertEquals(-20L, report(revenue = 120, target = 100).gap)
    }

    @Test
    fun `no target means no gap and no percentage`() {
        assertNull(report(revenue = 80, target = null).gap)
        assertNull(report(revenue = 80, target = null).percent)
    }

    @Test
    fun `a target of zero is not a target`() {
        // Dividing by it would give infinity, and showing that as a percentage
        // would be worse than showing nothing.
        assertNull(report(revenue = 80, target = 0).percent)
    }

    @Test
    fun `the percentage keeps what is over the target`() {
        assertEquals(120, report(revenue = 120, target = 100).percent)
    }
}
