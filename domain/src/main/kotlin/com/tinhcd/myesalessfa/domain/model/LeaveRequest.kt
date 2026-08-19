package com.tinhcd.myesalessfa.domain.model

import java.time.LocalDate

data class LeaveType(
    val leaveTypeId: String,
    val code: String,
    val name: String,
    val isPaid: Boolean,
)

enum class LeaveStatus { PENDING, APPROVED, REJECTED, CANCELLED }

data class LeaveRequest(
    val requestId: String,
    val leaveTypeId: String,
    val typeName: String,
    val isPaid: Boolean,
    val fromDate: LocalDate,
    val toDate: LocalDate,
    val reason: String,
    val status: LeaveStatus,
    /** Why it was refused, or a condition attached to an approval. */
    val decisionNote: String?,
    val decidedAtEpochMs: Long?,
) {
    /** Inclusive of both ends: a one-day absence is one day, not zero. */
    val days: Long get() = toDate.toEpochDay() - fromDate.toEpochDay() + 1

    /** The only state the rep can still act on themselves. */
    val canWithdraw: Boolean get() = status == LeaveStatus.PENDING
}

/**
 * A request being filled in.
 *
 * The overlap rule is not checked here. The device only knows the requests the
 * server last sent, and a rep with two phones — or one that has been offline —
 * would be told the week is free when it is not. The exclusion constraint on the
 * table is the only place that can answer it truthfully, so the screen asks and
 * reports what comes back.
 */
data class LeaveDraft(
    val leaveTypeId: String? = null,
    val fromDate: LocalDate? = null,
    val toDate: LocalDate? = null,
    val reason: String = "",
) {
    val periodError: String?
        get() = when {
            fromDate == null || toDate == null -> null
            toDate.isBefore(fromDate) -> "Ngày kết thúc phải sau ngày bắt đầu"
            else -> null
        }

    val days: Long?
        get() {
            val from = fromDate ?: return null
            val to = toDate ?: return null
            if (to.isBefore(from)) return null
            return to.toEpochDay() - from.toEpochDay() + 1
        }

    val canSubmit: Boolean
        get() = leaveTypeId != null &&
            fromDate != null &&
            toDate != null &&
            periodError == null &&
            reason.isNotBlank()
}
