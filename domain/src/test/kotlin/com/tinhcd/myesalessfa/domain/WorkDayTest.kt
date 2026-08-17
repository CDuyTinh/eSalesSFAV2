package com.tinhcd.myesalessfa.domain

import com.tinhcd.myesalessfa.domain.model.Branch
import com.tinhcd.myesalessfa.domain.model.CheckInGate
import com.tinhcd.myesalessfa.domain.model.GeoPoint
import com.tinhcd.myesalessfa.domain.model.ReasonKind
import com.tinhcd.myesalessfa.domain.model.WorkDay
import com.tinhcd.myesalessfa.domain.model.WorkDayPolicy
import com.tinhcd.myesalessfa.domain.model.WorkDayState
import com.tinhcd.myesalessfa.domain.usecase.EvaluateCheckInUseCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class WorkDayTest {

    private val depot = Branch(
        id = "b1",
        code = "CN01",
        name = "Nhà phân phối Minh Phát",
        address = null,
        lat = 10.7720,
        lng = 106.6580,
    )

    private val policy = WorkDayPolicy(
        branchRadiusM = 200,
        maxAccuracyM = 50,
        allowReasonWhenFar = true,
        lateAfter = null,
    )

    private fun day(
        checkIn: Long? = null,
        checkOut: Long? = null,
        openVisits: Int = 0,
    ) = WorkDay(
        date = LocalDate.of(2026, 8, 17),
        branch = depot,
        checkInAtEpochMs = checkIn,
        checkOutAtEpochMs = checkOut,
        openVisits = openVisits,
    )

    @Test
    fun `a day with no punches has not started`() {
        assertEquals(WorkDayState.NOT_STARTED, day().state)
    }

    @Test
    fun `a day with only a clock-in is open`() {
        assertEquals(WorkDayState.OPEN, day(checkIn = 1_000).state)
    }

    @Test
    fun `a day with both punches is closed`() {
        assertEquals(WorkDayState.CLOSED, day(checkIn = 1_000, checkOut = 2_000).state)
    }

    @Test
    fun `a closed day is not an open one`() {
        // The distinction the enum exists for: both of these have no day running,
        // and a bare `isOpen` would let the screen offer to close one twice.
        assertFalse(day().isOpen)
        assertFalse(day(checkIn = 1_000, checkOut = 2_000).isOpen)
    }

    @Test
    fun `the day cannot be closed over a visit still open`() {
        assertFalse(day(checkIn = 1_000, openVisits = 2).canCloseDay)
        assertTrue(day(checkIn = 1_000, openVisits = 0).canCloseDay)
    }

    @Test
    fun `a day that never started cannot be closed either`() {
        assertFalse(day(openVisits = 0).canCloseDay)
    }

    // -------------------------------------------------------------------------
    // The gate at the depot
    // -------------------------------------------------------------------------

    private val evaluate = EvaluateCheckInUseCase()

    @Test
    fun `standing at the depot is allowed`() {
        val gate = evaluate.atBranch(
            branch = depot,
            location = GeoPoint(10.7721, 106.6581, accuracyM = 8f),
            policy = policy,
        )

        assertTrue(gate is CheckInGate.Allowed)
    }

    @Test
    fun `the depot radius is its own, not the outlet one`() {
        // ~700m away: inside no shopfront radius, outside the 200m depot yard.
        val gate = evaluate.atBranch(
            branch = depot,
            location = GeoPoint(10.7783, 106.6580, accuracyM = 8f),
            policy = policy,
        )

        assertTrue(gate is CheckInGate.NeedsReason)
        assertEquals(ReasonKind.GPS_OUT_OF_RANGE, (gate as CheckInGate.NeedsReason).kind)
    }

    @Test
    fun `a wildly inaccurate fix is caught before the distance is`() {
        // The ordering that matters: complaining about 300 metres computed from a
        // 500-metre-accurate position tells the rep nothing they can act on.
        val gate = evaluate.atBranch(
            branch = depot,
            location = GeoPoint(10.7783, 106.6580, accuracyM = 500f),
            policy = policy,
        )

        assertEquals(
            ReasonKind.GPS_LOW_ACCURACY,
            (gate as CheckInGate.NeedsReason).kind,
        )
    }

    @Test
    fun `a depot with no coordinates lets the rep through`() {
        // Normal in a market that has not geocoded its depots. Trapping the rep
        // outside a building the database cannot place would be the worse answer.
        val gate = evaluate.atBranch(
            branch = depot.copy(lat = null, lng = null),
            location = GeoPoint(10.0, 106.0, accuracyM = 8f),
            policy = policy,
        )

        assertTrue(gate is CheckInGate.Allowed)
    }

    @Test
    fun `no fix at all asks for a reason rather than blocking`() {
        val gate = evaluate.atBranch(branch = depot, location = null, policy = policy)

        assertEquals(
            ReasonKind.GPS_UNAVAILABLE,
            (gate as CheckInGate.NeedsReason).kind,
        )
    }

    @Test
    fun `a market that forbids far punches blocks instead of asking`() {
        val gate = evaluate.atBranch(
            branch = depot,
            location = GeoPoint(10.7783, 106.6580, accuracyM = 8f),
            policy = policy.copy(allowReasonWhenFar = false),
        )

        assertTrue(gate is CheckInGate.Blocked)
    }
}
