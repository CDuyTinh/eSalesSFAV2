package com.tinhcd.myesalessfa.domain

import com.tinhcd.myesalessfa.domain.model.WorkNote
import com.tinhcd.myesalessfa.domain.model.WorkNoteCompletion
import com.tinhcd.myesalessfa.domain.model.WorkNoteDraft
import com.tinhcd.myesalessfa.domain.model.WorkNoteStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class WorkNoteTest {

    private val today = LocalDate.of(2026, 8, 18)

    private fun note(
        status: WorkNoteStatus = WorkNoteStatus.OPEN,
        due: LocalDate? = null,
    ) = WorkNote(
        noteId = "n1",
        title = "Đòi kệ trưng bày",
        body = null,
        dueOn = due,
        status = status,
        result = if (status == WorkNoteStatus.DONE) "Đã xong" else null,
        doneAtEpochMs = null,
        createdAtEpochMs = null,
        customerId = null,
        customerName = null,
    )

    @Test
    fun `an open note past its date is overdue`() {
        assertTrue(note(due = LocalDate.of(2026, 8, 17)).isOverdue(today))
    }

    @Test
    fun `a note due today is not overdue yet`() {
        assertFalse(note(due = today).isOverdue(today))
        assertTrue(note(due = today).isDueToday(today))
    }

    @Test
    fun `a closed note cannot be overdue`() {
        // Otherwise a finished list keeps shouting in red about work already done.
        val done = note(status = WorkNoteStatus.DONE, due = LocalDate.of(2026, 8, 1))

        assertFalse(done.isOverdue(today))
        assertFalse(done.isDueToday(today))
    }

    @Test
    fun `a note with no date is never overdue`() {
        // Something to get to, not something late.
        assertFalse(note(due = null).isOverdue(today))
    }

    // -------------------------------------------------------------------------
    // Writing one
    // -------------------------------------------------------------------------

    @Test
    fun `a note needs something written on it`() {
        assertNotNull(WorkNoteDraft(title = "").titleError)
        assertNotNull(WorkNoteDraft(title = "   ").titleError)
        assertFalse(WorkNoteDraft(title = "  ").canSubmit)
    }

    @Test
    fun `a title alone is enough`() {
        val draft = WorkNoteDraft(title = "Gọi lại NPP")

        assertNull(draft.titleError)
        assertTrue(draft.canSubmit)
    }

    // -------------------------------------------------------------------------
    // Closing one
    // -------------------------------------------------------------------------

    @Test
    fun `closing a note requires an outcome`() {
        // The database refuses it too, but the constraint's message is not
        // something a rep can act on; this is what makes the button wait.
        assertFalse(WorkNoteCompletion("n1", result = "").canSubmit)
        assertFalse(WorkNoteCompletion("n1", result = "   ").canSubmit)
    }

    @Test
    fun `any written outcome will do`() {
        assertTrue(WorkNoteCompletion("n1", result = "Chủ shop đồng ý").canSubmit)
    }
}
