package com.tinhcd.myesalessfa.domain

import com.tinhcd.myesalessfa.domain.model.DailyTargetPlan
import com.tinhcd.myesalessfa.domain.model.DailyTargetStop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class DailyTargetPlanTest {

    private fun stop(
        id: String,
        target: Long = 0,
        hasTarget: Boolean = false,
        last: Long? = null,
    ) = DailyTargetStop(
        customerId = id,
        customerCode = "KH$id",
        customerName = "Outlet $id",
        address = null,
        visitOrder = 1,
        target = target,
        hasTarget = hasTarget,
        lastAmount = last,
        lastDate = last?.let { LocalDate.of(2026, 8, 11) },
    )

    private val saved = stop("a", target = 1_000_000, hasTarget = true, last = 900_000)
    private val blank = stop("b")

    private fun plan(vararg edits: Pair<String, Long>) = DailyTargetPlan(
        date = LocalDate.of(2026, 8, 18),
        stops = listOf(saved, blank),
        edits = edits.toMap(),
    )

    @Test
    fun `an untouched outlet shows what was saved`() {
        assertEquals(1_000_000L, plan().amountFor(saved))
        assertEquals(0L, plan().amountFor(blank))
    }

    @Test
    fun `an edit wins over what was saved`() {
        assertEquals(1_500_000L, plan("a" to 1_500_000L).amountFor(saved))
    }

    @Test
    fun `the total counts edits and saved figures together`() {
        assertEquals(1_400_000L, plan("b" to 400_000L).total)
    }

    @Test
    fun `only outlets with a figure count as planned`() {
        assertEquals(1, plan().plannedCount)
        assertEquals(2, plan("b" to 1L).plannedCount)
    }

    // -------------------------------------------------------------------------
    // What actually gets sent
    // -------------------------------------------------------------------------

    @Test
    fun `nothing to save when nothing was touched`() {
        assertTrue(plan().changed.isEmpty())
        assertFalse(plan().canSave)
    }

    @Test
    fun `re-typing the same figure is not a change`() {
        // Sending it would touch updated_at on an outlet nobody rethought, and
        // that timestamp is the only evidence of when a rep last considered one.
        assertTrue(plan("a" to 1_000_000L).changed.isEmpty())
        assertFalse(plan("a" to 1_000_000L).canSave)
    }

    @Test
    fun `a revised figure is sent`() {
        assertEquals(mapOf("a" to 1_500_000L), plan("a" to 1_500_000L).changed)
    }

    @Test
    fun `clearing a saved figure to zero is sent`() {
        // The rep decided this outlet is not worth a target today. That is a
        // decision, and dropping it would silently keep yesterday's number.
        assertEquals(mapOf("a" to 0L), plan("a" to 0L).changed)
    }

    @Test
    fun `typing zero into an outlet that never had one is not a change`() {
        // Nothing was there and nothing is there. Sending it would create a row
        // saying the rep planned nothing, which is not the same as not planning.
        assertTrue(plan("b" to 0L).changed.isEmpty())
    }

    @Test
    fun `an edit for an outlet not on the route is ignored`() {
        // Defensive: the route was reloaded and this stop is gone.
        assertTrue(plan("ghost" to 500L).changed.isEmpty())
    }

    // -------------------------------------------------------------------------
    // The suggestion
    // -------------------------------------------------------------------------

    @Test
    fun `the suggestion is the last sale, not a computed recommendation`() {
        assertEquals(900_000L, plan().suggestionFor(saved))
    }

    @Test
    fun `no history means no suggestion`() {
        assertNull(plan().suggestionFor(blank))
        assertNull(plan().suggestionFor(stop("c", last = 0)))
    }
}
