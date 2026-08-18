package com.tinhcd.myesalessfa.domain.model

import java.time.LocalDate

enum class WorkNoteStatus { OPEN, DONE }

/**
 * One thing the rep meant to come back to.
 *
 * [result] is present exactly when [status] is DONE — the database enforces the
 * pair, because a note marked done that says nothing about what was done is
 * useless to the person reading it three months later, and that person is
 * usually the rep who wrote it.
 */
data class WorkNote(
    val noteId: String,
    val title: String,
    val body: String?,
    val dueOn: LocalDate?,
    val status: WorkNoteStatus,
    val result: String?,
    val doneAtEpochMs: Long?,
    val createdAtEpochMs: Long?,
    val customerId: String?,
    val customerName: String?,
) {
    val isOpen: Boolean get() = status == WorkNoteStatus.OPEN

    /** Overdue only while it is still open; a closed note cannot be late any more. */
    fun isOverdue(today: LocalDate): Boolean =
        isOpen && dueOn != null && dueOn.isBefore(today)

    fun isDueToday(today: LocalDate): Boolean = isOpen && dueOn == today
}

/** A note being written. */
data class WorkNoteDraft(
    val title: String = "",
    val body: String = "",
    val dueOn: LocalDate? = null,
    val customerId: String? = null,
) {
    val titleError: String?
        get() = if (title.isBlank()) "Nhập nội dung công việc" else null

    val canSubmit: Boolean get() = titleError == null
}

/**
 * Closing a note.
 *
 * The outcome is required, and refusing an empty one here rather than letting the
 * server do it is the difference between a rep being told what to type and a rep
 * being told a constraint was violated.
 */
data class WorkNoteCompletion(
    val noteId: String,
    val result: String = "",
) {
    val canSubmit: Boolean get() = result.isNotBlank()
}
