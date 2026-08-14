package com.tinhcd.myesalessfa.data.repository

import com.tinhcd.myesalessfa.data.remote.CustomerDto
import com.tinhcd.myesalessfa.data.remote.RouteCustomerDto
import com.tinhcd.myesalessfa.data.remote.VisitDto
import com.tinhcd.myesalessfa.domain.DataResult
import com.tinhcd.myesalessfa.domain.model.Customer
import com.tinhcd.myesalessfa.domain.model.RouteStop
import com.tinhcd.myesalessfa.domain.model.VisitStatus
import com.tinhcd.myesalessfa.domain.repository.RouteRepository
import com.tinhcd.myesalessfa.data.remote.Filters
import com.tinhcd.myesalessfa.data.remote.PostgrestService
import java.time.LocalDate
import java.time.OffsetDateTime
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RouteRepositoryImpl @Inject constructor(
    private val service: PostgrestService,
) : RouteRepository {

    /**
     * Two round trips rather than one clever join: the stops for the weekday,
     * and today's visit rows. RLS already limits both to the signed-in rep, so
     * neither query carries a branch or salesperson filter — that is the whole
     * point of pushing scoping into the database.
     */
    override suspend fun getRoute(date: LocalDate): DataResult<List<RouteStop>> = try {
        val weekday = date.dayOfWeek.value // ISO: Monday = 1

        val stops = service.routeCustomers(
            visitWeekdays = Filters.arrayContains(weekday),
        )

        val visits = service.visits(visitDate = Filters.eq(date.toString()))
            .associateBy { it.customerId }

        DataResult.Success(
            stops.map { stop ->
                val visit = visits[stop.customer.id]
                RouteStop(
                    customer = stop.customer.toDomain(),
                    visitOrder = stop.visitOrder,
                    status = visit?.status.toVisitStatus(),
                    visitId = visit?.id,
                    checkInAtEpochMs = visit?.checkInAt?.toEpochMillisOrNull(),
                    checkOutAtEpochMs = visit?.checkOutAt?.toEpochMillisOrNull(),
                )
            },
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
)

private fun String?.toVisitStatus(): VisitStatus = when (this) {
    "in_progress" -> VisitStatus.IN_PROGRESS
    "completed" -> VisitStatus.COMPLETED
    "no_order" -> VisitStatus.NO_ORDER
    "closed" -> VisitStatus.CLOSED
    else -> VisitStatus.PLANNED
}

private fun String.toEpochMillisOrNull(): Long? =
    runCatching { OffsetDateTime.parse(this).toInstant().toEpochMilli() }.getOrNull()
