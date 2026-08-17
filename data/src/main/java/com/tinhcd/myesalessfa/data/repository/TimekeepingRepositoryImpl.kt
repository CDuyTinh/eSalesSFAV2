package com.tinhcd.myesalessfa.data.repository

import com.tinhcd.myesalessfa.data.remote.dto.BranchDto
import com.tinhcd.myesalessfa.data.remote.dto.WorkDayDto
import com.tinhcd.myesalessfa.data.remote.dto.WorkDayPunchDto
import com.tinhcd.myesalessfa.data.remote.http.orThrow
import com.tinhcd.myesalessfa.data.remote.service.TimekeepingService
import com.tinhcd.myesalessfa.domain.DataResult
import com.tinhcd.myesalessfa.domain.model.Branch
import com.tinhcd.myesalessfa.domain.model.WorkDay
import com.tinhcd.myesalessfa.domain.model.WorkDayPunch
import com.tinhcd.myesalessfa.domain.repository.TimekeepingRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Not cached beyond the flow it publishes.
 *
 * The day's state changes exactly twice, and both times through this class, so the
 * flow is refreshed from the server after each punch rather than patched locally.
 * Patching would let the app believe the day is open when the insert was in fact
 * refused — and the whole point of this screen is that the belief is the record.
 */
@Singleton
class TimekeepingRepositoryImpl @Inject constructor(
    private val service: TimekeepingService,
) : TimekeepingRepository {

    private val _today = MutableStateFlow<WorkDay?>(null)
    override val today: StateFlow<WorkDay?> = _today.asStateFlow()

    override suspend fun refresh(date: LocalDate): DataResult<WorkDay> = try {
        val day = service.workDay(date.toString()).orThrow().toDomain()
        _today.value = day
        DataResult.Success(day)
    } catch (e: Exception) {
        DataResult.Failure(e.toAppError())
    }

    override suspend fun openDay(punch: WorkDayPunch) = submit("check_in", punch)

    override suspend fun closeDay(punch: WorkDayPunch) = submit("check_out", punch)

    private suspend fun submit(type: String, punch: WorkDayPunch): DataResult<Unit> = try {
        service.submitPunch(
            WorkDayPunchDto(
                type = type,
                workDate = punch.date.toString(),
                happenedAt = OffsetDateTime.now(ZoneOffset.UTC).toString(),
                lat = punch.point?.lat,
                lng = punch.point?.lng,
                accuracyM = punch.point?.accuracyM?.toDouble(),
                distanceM = punch.distanceM,
                reasonId = punch.reasonId,
            ),
        ).orThrow()

        // Deliberately not checked: the punch landed, and a failed re-read should
        // not be reported as a failed punch. The screen leaves on success either
        // way, and the shell reads the flow again when it next resumes.
        refresh(punch.date)
        DataResult.Success(Unit)
    } catch (e: Exception) {
        DataResult.Failure(e.toAppError())
    }
}

private fun WorkDayDto.toDomain() = WorkDay(
    date = LocalDate.parse(workDate),
    branch = branch.toDomain(),
    checkInAtEpochMs = checkInAt?.toEpochMillisOrNull(),
    checkOutAtEpochMs = checkOutAt?.toEpochMillisOrNull(),
    openVisits = openVisits,
)

private fun BranchDto.toDomain() = Branch(
    id = id,
    code = code,
    name = name,
    address = address,
    lat = lat,
    lng = lng,
)

private fun String.toEpochMillisOrNull(): Long? =
    runCatching { OffsetDateTime.parse(this).toInstant().toEpochMilli() }.getOrNull()
