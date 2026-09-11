package com.tinhcd.myesalessfa.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules the form and the list enforce before the server ever sees them.
 *
 * The state machine itself lives in Postgres, where it cannot be walked around;
 * what is here is the client's reading of a plan — what may be done to it, and
 * whether a draft is complete enough to send.
 */
class WorkPlanTest {

    private val customerType = WorkWithCategory(
        id = "c1",
        code = "WW-ROUTE",
        name = "Đi tuyến cùng khách hàng",
        objectKind = WorkPlanObjectKind.CUSTOMER,
    )

    private val noObjectType = WorkWithCategory(
        id = "c2",
        code = "WW-TRAIN",
        name = "Đào tạo nội bộ",
    )

    private val repType = WorkWithCategory(
        id = "c3",
        code = "WW-COACH",
        name = "Kèm cặp",
        fieldTraining = true,
        objectKind = WorkPlanObjectKind.SALESPERSON,
    )

    private fun outlet(id: String) = WorkPlanObject(
        kind = WorkPlanObjectKind.CUSTOMER,
        targetId = id,
        name = "Khách $id",
    )

    private fun plan(
        status: WorkPlanStatus,
        assigned: Boolean = false,
    ) = WorkPlan(
        id = "p1",
        categoryId = "c1",
        categoryCode = "WW-ROUTE",
        categoryName = "Đi tuyến",
        content = "Nội dung",
        fromAt = "2026-09-12T08:00:00+07:00",
        toAt = "2026-09-12T12:00:00+07:00",
        status = status,
        assignedToMe = assigned,
    )

    private fun draft(
        category: WorkWithCategory? = customerType,
        content: String = "Đi tuyến",
        from: String = "2026-09-12T08:00:00+07:00",
        to: String = "2026-09-12T12:00:00+07:00",
        objects: List<WorkPlanObject> = listOf(outlet("k1")),
    ) = DraftWorkPlan(
        category = category,
        content = content,
        fromAt = from,
        toAt = to,
        objects = objects,
    )

    // -------------------------------------------------------------------------
    // What may be done to a plan
    // -------------------------------------------------------------------------

    @Test
    fun `only an unsent plan the rep wrote can be edited`() {
        assertTrue(plan(WorkPlanStatus.DRAFT).canEdit)
        assertFalse(plan(WorkPlanStatus.SUBMITTED).canEdit)
        assertFalse(plan(WorkPlanStatus.APPROVED).canEdit)
        assertFalse(plan(WorkPlanStatus.COMPLETED).canEdit)
    }

    /** A plan head office wrote is the rep's to carry out, not to rewrite. */
    @Test
    fun `an assigned draft cannot be edited`() {
        assertFalse(plan(WorkPlanStatus.DRAFT, assigned = true).canEdit)
    }

    @Test
    fun `an assigned plan can still be completed`() {
        assertTrue(plan(WorkPlanStatus.APPROVED, assigned = true).canClose)
    }

    @Test
    fun `closing out needs an approved or submitted plan`() {
        assertFalse(plan(WorkPlanStatus.DRAFT).canClose)
        assertTrue(plan(WorkPlanStatus.SUBMITTED).canClose)
        assertTrue(plan(WorkPlanStatus.APPROVED).canClose)
        assertFalse(plan(WorkPlanStatus.CANCELLED).canClose)
        assertFalse(plan(WorkPlanStatus.COMPLETED).canClose)
    }

    @Test
    fun `a finished plan cannot be cancelled`() {
        assertTrue(plan(WorkPlanStatus.DRAFT).canCancel)
        assertTrue(plan(WorkPlanStatus.APPROVED).canCancel)
        assertFalse(plan(WorkPlanStatus.COMPLETED).canCancel)
        assertFalse(plan(WorkPlanStatus.REJECTED).canCancel)
    }

    @Test
    fun `unknown status names do not crash the list`() {
        assertEquals(WorkPlanStatus.DRAFT, WorkPlanStatus.fromWire("something_else"))
        assertEquals(WorkPlanStatus.COMPLETED, WorkPlanStatus.fromWire("completed"))
        assertNull(WorkPlanObjectKind.fromWire("distributor"))
        assertNull(WorkPlanObjectKind.fromWire(null))
    }

    // -------------------------------------------------------------------------
    // The form
    // -------------------------------------------------------------------------

    @Test
    fun `a complete draft can be saved`() {
        assertTrue(draft().canSave)
    }

    @Test
    fun `a draft with no work type cannot be saved`() {
        assertFalse(draft(category = null).canSave)
    }

    @Test
    fun `a draft with no content cannot be saved`() {
        assertFalse(draft(content = "   ").canSave)
    }

    @Test
    fun `a window that ends before it starts cannot be saved`() {
        assertFalse(
            draft(
                from = "2026-09-12T12:00:00+07:00",
                to = "2026-09-12T08:00:00+07:00",
            ).canSave,
        )
    }

    /** Equal ends is not a window either — a plan of zero length plans nothing. */
    @Test
    fun `a window of no length cannot be saved`() {
        val same = "2026-09-12T08:00:00+07:00"
        assertFalse(draft(from = same, to = same).canSave)
    }

    @Test
    fun `a work type that needs a customer cannot be saved without one`() {
        assertFalse(draft(objects = emptyList()).canSave)
    }

    @Test
    fun `a work type with no object is complete without one`() {
        assertTrue(draft(category = noObjectType, objects = emptyList()).canSave)
    }

    // -------------------------------------------------------------------------
    // Objects
    // -------------------------------------------------------------------------

    @Test
    fun `the same outlet cannot be added twice`() {
        val d = DraftWorkPlan(category = customerType).withObject(outlet("k1"))
        assertEquals(1, d.withObject(outlet("k1")).objects.size)
    }

    @Test
    fun `tapping a chosen outlet takes it off`() {
        val d = DraftWorkPlan(category = customerType)
            .withObject(outlet("k1"))
            .withObject(outlet("k2"))

        assertEquals(listOf("k2"), d.toggleObject(outlet("k1")).objects.map { it.targetId })
    }

    /**
     * Changing the work type to one that wants a different kind of object drops
     * what was chosen. Keeping two customers under "kèm cặp nhân viên" would
     * send the server rows it will refuse.
     */
    @Test
    fun `switching to a work type of another kind clears the objects`() {
        val d = draft().withCategory(repType)

        assertTrue(d.objects.isEmpty())
        assertEquals(WorkPlanObjectKind.SALESPERSON, d.objectKind)
    }

    @Test
    fun `switching to a work type of the same kind keeps them`() {
        val other = customerType.copy(id = "c9", code = "WW-TRADE", name = "Trưng bày")
        assertEquals(1, draft().withCategory(other).objects.size)
    }

    @Test
    fun `switching to a work type with no object clears them too`() {
        assertTrue(draft().withCategory(noObjectType).objects.isEmpty())
    }

    // -------------------------------------------------------------------------
    // The board
    // -------------------------------------------------------------------------

    @Test
    fun `only statuses that occur get a chip`() {
        val board = WorkPlanBoard(
            counts = mapOf(
                WorkPlanStatus.DRAFT to 2,
                WorkPlanStatus.COMPLETED to 1,
            ),
        )

        assertEquals(
            listOf(WorkPlanStatus.DRAFT, WorkPlanStatus.COMPLETED),
            board.presentStatuses,
        )
        assertEquals(0, board.count(WorkPlanStatus.REJECTED))
    }

    @Test
    fun `the calendar answers for a day it knows and not for one it does not`() {
        val board = WorkPlanBoard(
            calendar = listOf(WorkPlanDay("2026-09-12", WorkPlanStatus.APPROVED, 2)),
        )

        assertEquals(2, board.dayOf("2026-09-12")?.planCount)
        assertNull(board.dayOf("2026-09-13"))
    }

    @Test
    fun `removing an outlet that was never there changes nothing`() {
        val d = draft()
        assertEquals(d.objects, d.withoutObject("nobody").objects)
    }

    @Test
    fun `a plan still asking for something is open`() {
        assertTrue(WorkPlanStatus.DRAFT.isOpen)
        assertTrue(WorkPlanStatus.SUBMITTED.isOpen)
        assertTrue(WorkPlanStatus.APPROVED.isOpen)
        assertFalse(WorkPlanStatus.COMPLETED.isOpen)
        assertFalse(WorkPlanStatus.REJECTED.isOpen)
        assertFalse(WorkPlanStatus.CANCELLED.isOpen)
    }

    @Test
    fun `adding an outlet that is already chosen returns the same draft`() {
        val d = draft()
        assertSame(d, d.withObject(outlet("k1")))
    }
}
