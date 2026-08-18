package com.tinhcd.myesalessfa.data.repository

import com.tinhcd.myesalessfa.data.remote.dto.DailyTargetEntryDto
import com.tinhcd.myesalessfa.data.remote.dto.DailyTargetStopDto
import com.tinhcd.myesalessfa.data.remote.dto.SaveDailyTargetsDto
import com.tinhcd.myesalessfa.data.remote.http.orThrow
import com.tinhcd.myesalessfa.data.remote.service.DailyTargetService
import com.tinhcd.myesalessfa.domain.DataResult
import com.tinhcd.myesalessfa.domain.model.DailyTargetStop
import com.tinhcd.myesalessfa.domain.repository.DailyTargetRepository
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DailyTargetRepositoryImpl @Inject constructor(
    private val service: DailyTargetService,
) : DailyTargetRepository {

    override suspend fun stops(date: LocalDate): DataResult<List<DailyTargetStop>> = try {
        DataResult.Success(service.stops(date.toString()).orThrow().stops.map { it.toDomain() })
    } catch (e: Exception) {
        DataResult.Failure(e.toAppError())
    }

    override suspend fun save(
        date: LocalDate,
        targets: Map<String, Long>,
    ): DataResult<Unit> = try {
        service.save(
            SaveDailyTargetsDto(
                date = date.toString(),
                targets = targets.map { (customerId, amount) ->
                    DailyTargetEntryDto(customerId = customerId, targetAmount = amount)
                },
            ),
        ).orThrow()
        DataResult.Success(Unit)
    } catch (e: Exception) {
        DataResult.Failure(e.toAppError())
    }
}

private fun DailyTargetStopDto.toDomain() = DailyTargetStop(
    customerId = customerId,
    customerCode = customerCode,
    customerName = customerName,
    address = address,
    visitOrder = visitOrder,
    target = target,
    hasTarget = hasTarget,
    lastAmount = lastAmount,
    lastDate = lastDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
)
