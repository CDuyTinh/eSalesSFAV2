package com.tinhcd.myesalessfa.domain

import com.tinhcd.myesalessfa.domain.model.Customer
import com.tinhcd.myesalessfa.domain.model.RouteStop
import com.tinhcd.myesalessfa.domain.model.VisitStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteStopTest {

    private fun stop(
        status: VisitStatus = VisitStatus.PLANNED,
        visitId: String? = null,
        checkInPending: Boolean = false,
    ) = RouteStop(
        customer = Customer(
            id = "c1", code = "KH001", name = "Tap hoa", address = null, phone = null,
            lat = null, lng = null, avatarUrl = null, checkInRadiusM = null,
        ),
        visitOrder = 1,
        status = status,
        visitId = visitId,
        checkInAtEpochMs = null,
        checkOutAtEpochMs = null,
        checkInPending = checkInPending,
    )

    @Test
    fun `a queued check-in with no visit id yet is waiting to sync`() {
        // Nothing more can happen at this stop: there is no visit id to open the
        // workflow with, and checking in again would queue a duplicate that the
        // database refuses on (customer, salesperson, date).
        assertTrue(stop(checkInPending = true).isWaitingToSync)
    }

    @Test
    fun `a stop with no queued check-in is not waiting on anything`() {
        assertFalse(stop().isWaitingToSync)
        assertFalse(stop(status = VisitStatus.COMPLETED, visitId = "v1").isWaitingToSync)
    }

    @Test
    fun `once the visit id arrives the stop stops waiting`() {
        // The flush landed and the route has been re-read, so the workflow can be
        // opened even though the outbox entry may not have been pruned yet.
        assertFalse(
            stop(
                status = VisitStatus.IN_PROGRESS,
                visitId = "v1",
                checkInPending = true,
            ).isWaitingToSync,
        )
    }
}
