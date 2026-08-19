package com.tinhcd.myesalessfa.data.repository

import com.tinhcd.myesalessfa.data.remote.dto.LeaveRequestDto
import com.tinhcd.myesalessfa.data.remote.dto.LeaveTypeDto
import com.tinhcd.myesalessfa.data.remote.dto.NewLeaveRequestDto
import com.tinhcd.myesalessfa.data.remote.dto.WithdrawLeaveDto
import com.tinhcd.myesalessfa.data.remote.http.orThrow
import com.tinhcd.myesalessfa.data.remote.service.LeaveService
import com.tinhcd.myesalessfa.domain.DataResult
import com.tinhcd.myesalessfa.domain.model.LeaveDraft
import com.tinhcd.myesalessfa.domain.model.LeaveRequest
import com.tinhcd.myesalessfa.domain.model.LeaveStatus
import com.tinhcd.myesalessfa.domain.model.LeaveType
import com.tinhcd.myesalessfa.domain.repository.LeaveBoard
import com.tinhcd.myesalessfa.domain.repository.LeaveRepository
import java.time.LocalDate
import java.time.OffsetDateTime
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LeaveRepositoryImpl @Inject constructor(
    private val service: LeaveService,
) : LeaveRepository {

    override suspend fun board(): DataResult<LeaveBoard> = try {
        val dto = service.board().orThrow()
        DataResult.Success(
            LeaveBoard(
                types = dto.types.map { it.toDomain() },
                requests = dto.requests.map { it.toDomain() },
            ),
        )
    } catch (e: Exception) {
        DataResult.Failure(e.toAppError())
    }

    /**
     * The draft is validated before this is called, so the non-null assertions
     * below are the screen's guarantee rather than an assumption: `canSubmit` is
     * false until all three are present.
     */
    override suspend fun submit(draft: LeaveDraft): DataResult<Unit> = try {
        service.submit(
            NewLeaveRequestDto(
                leaveTypeId = requireNotNull(draft.leaveTypeId),
                fromDate = requireNotNull(draft.fromDate).toString(),
                toDate = requireNotNull(draft.toDate).toString(),
                reason = draft.reason.trim(),
            ),
        ).orThrow()
        DataResult.Success(Unit)
    } catch (e: Exception) {
        DataResult.Failure(e.toAppError())
    }

    override suspend fun withdraw(requestId: String): DataResult<Unit> = try {
        service.withdraw(WithdrawLeaveDto(requestId = requestId)).orThrow()
        DataResult.Success(Unit)
    } catch (e: Exception) {
        DataResult.Failure(e.toAppError())
    }
}

private fun LeaveTypeDto.toDomain() = LeaveType(
    leaveTypeId = leaveTypeId,
    code = code,
    name = name,
    isPaid = isPaid,
)

private fun LeaveRequestDto.toDomain() = LeaveRequest(
    requestId = requestId,
    leaveTypeId = leaveTypeId,
    typeName = typeName,
    isPaid = isPaid,
    fromDate = LocalDate.parse(fromDate),
    toDate = LocalDate.parse(toDate),
    reason = reason,
    status = status.toLeaveStatus(),
    decisionNote = decisionNote,
    decidedAtEpochMs = decidedAt?.let {
        runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull()
    },
)

private fun String.toLeaveStatus(): LeaveStatus = when (this) {
    "approved" -> LeaveStatus.APPROVED
    "rejected" -> LeaveStatus.REJECTED
    "cancelled" -> LeaveStatus.CANCELLED
    else -> LeaveStatus.PENDING
}
