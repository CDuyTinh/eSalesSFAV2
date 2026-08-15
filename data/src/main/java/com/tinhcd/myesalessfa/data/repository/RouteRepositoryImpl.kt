package com.tinhcd.myesalessfa.data.repository

import com.tinhcd.myesalessfa.data.remote.CustomerDto
import com.tinhcd.myesalessfa.data.remote.FunctionsService
import com.tinhcd.myesalessfa.data.remote.RouteStopDto
import com.tinhcd.myesalessfa.domain.DataResult
import com.tinhcd.myesalessfa.domain.model.Customer
import com.tinhcd.myesalessfa.domain.model.RouteStop
import com.tinhcd.myesalessfa.domain.model.VisitStatus
import com.tinhcd.myesalessfa.domain.repository.RouteRepository
import java.time.LocalDate
import java.time.OffsetDateTime
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RouteRepositoryImpl @Inject constructor(
    private val service: FunctionsService,
) : RouteRepository {

    /**
     * One call. This used to be two round trips plus a client-side join — the
     * stops for the weekday, today's visit rows, matched by customer id. The
     * function does that join next to the data, and derives the weekday from the
     * date so the two can no longer disagree.
     *
     * Still no branch or salesperson filter anywhere: RLS scopes it, on both sides
     * of the function boundary.
     */
    override suspend fun getRoute(date: LocalDate): DataResult<List<RouteStop>> = try {
        DataResult.Success(
            service.route(date.toString()).stops.map { it.toDomain() },
        )
    } catch (e: Exception) {
        DataResult.Failure(e.toAppError())
    }

    override suspend fun getStop(customerId: String, date: LocalDate): DataResult<RouteStop?> =
        when (val all = getRoute(date)) {
            is DataResult.Success ->
                DataResult.Success(all.data.firstOrNull { it.customer.id == customerId })

            is DataResult.Failure -> all
        }
}

private fun RouteStopDto.toDomain() = RouteStop(
    customer = customer.toDomain(),
    visitOrder = visitOrder,
    status = status.toVisitStatus(),
    visitId = visitId,
    checkInAtEpochMs = checkInAt?.toEpochMillisOrNull(),
    checkOutAtEpochMs = checkOutAt?.toEpochMillisOrNull(),
)

private fun CustomerDto.toDomain() = Customer(
    id = id,
    code = code,
    name = name,
    address = address,
    phone = phone,
    lat = lat,
    lng = lng,
    avatarUrl = avatarUrl,
    checkInRadiusM = checkInRadiusM,
    classId = classId,
    channelId = channelId,
    shopTypeId = shopTypeId,
)

private fun String.toVisitStatus(): VisitStatus = when (this) {
    "in_progress" -> VisitStatus.IN_PROGRESS
    "completed" -> VisitStatus.COMPLETED
    "no_order" -> VisitStatus.NO_ORDER
    "closed" -> VisitStatus.CLOSED
    "abandoned" -> VisitStatus.ABANDONED
    else -> VisitStatus.PLANNED
}

private fun String.toEpochMillisOrNull(): Long? =
    runCatching { OffsetDateTime.parse(this).toInstant().toEpochMilli() }.getOrNull()
