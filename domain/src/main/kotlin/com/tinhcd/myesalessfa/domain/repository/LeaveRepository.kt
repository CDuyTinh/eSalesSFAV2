package com.tinhcd.myesalessfa.domain.repository

import com.tinhcd.myesalessfa.domain.DataResult
import com.tinhcd.myesalessfa.domain.model.LeaveDraft
import com.tinhcd.myesalessfa.domain.model.LeaveRequest
import com.tinhcd.myesalessfa.domain.model.LeaveType

/** What came back together: the vocabulary, and the rep's own requests. */
data class LeaveBoard(
    val types: List<LeaveType> = emptyList(),
    val requests: List<LeaveRequest> = emptyList(),
)

/**
 * Asking for time off. There is no approve here: deciding is head office's, and
 * the table refuses any other transition from a signed-in rep.
 */
interface LeaveRepository {

    suspend fun board(): DataResult<LeaveBoard>

    suspend fun submit(draft: LeaveDraft): DataResult<Unit>

    suspend fun withdraw(requestId: String): DataResult<Unit>
}
