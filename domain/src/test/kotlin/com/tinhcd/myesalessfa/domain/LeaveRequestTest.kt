package com.tinhcd.myesalessfa.domain

import com.tinhcd.myesalessfa.domain.model.LeaveDraft
import com.tinhcd.myesalessfa.domain.model.LeaveRequest
import com.tinhcd.myesalessfa.domain.model.LeaveStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class LeaveRequestTest {

    private fun request(status: LeaveStatus, from: LocalDate, to: LocalDate) = LeaveRequest(
        requestId = "r1",
        leaveTypeId = "t1",
        typeName = "Nghỉ phép năm",
        isPaid = true,
        fromDate = from,
        toDate = to,
        reason = "Việc gia đình",
        status = status,
        decisionNote = null,
        decidedAtEpochMs = null,
    )

    @Test
    fun `a one-day absence is one day, not zero`() {
        val day = LocalDate.of(2026, 8, 24)

        assertEquals(1L, request(LeaveStatus.PENDING, day, day).days)
    }

    @Test
    fun `both ends are counted`() {
        val r = request(
            LeaveStatus.PENDING,
            LocalDate.of(2026, 8, 24),
            LocalDate.of(2026, 8, 26),
        )

        assertEquals(3L, r.days)
    }

    @Test
    fun `only a pending request can be withdrawn`() {
        val day = LocalDate.of(2026, 8, 24)

        assertTrue(request(LeaveStatus.PENDING, day, day).canWithdraw)
        // Cancelling an approved absence is a conversation with a manager, not a
        // button — and the table's trigger refuses it regardless.
        assertFalse(request(LeaveStatus.APPROVED, day, day).canWithdraw)
        assertFalse(request(LeaveStatus.REJECTED, day, day).canWithdraw)
        assertFalse(request(LeaveStatus.CANCELLED, day, day).canWithdraw)
    }

    // -------------------------------------------------------------------------
    // The form
    // -------------------------------------------------------------------------

    private val good = LeaveDraft(
        leaveTypeId = "t1",
        fromDate = LocalDate.of(2026, 8, 24),
        toDate = LocalDate.of(2026, 8, 26),
        reason = "Về quê",
    )

    @Test
    fun `a complete form can be submitted`() {
        assertTrue(good.canSubmit)
        assertEquals(3L, good.days)
        assertNull(good.periodError)
    }

    @Test
    fun `every field is required`() {
        assertFalse(good.copy(leaveTypeId = null).canSubmit)
        assertFalse(good.copy(fromDate = null).canSubmit)
        assertFalse(good.copy(toDate = null).canSubmit)
        assertFalse(good.copy(reason = "   ").canSubmit)
    }

    @Test
    fun `the period cannot run backwards`() {
        val backwards = good.copy(toDate = LocalDate.of(2026, 8, 23))

        assertNotNull(backwards.periodError)
        assertNull(backwards.days)
        assertFalse(backwards.canSubmit)
    }

    @Test
    fun `a half-filled period is not yet an error`() {
        // Nothing should turn red before the rep has picked both ends.
        assertNull(good.copy(toDate = null).periodError)
    }
}
