package com.tinhcd.myesalessfa.data.repository

import com.tinhcd.myesalessfa.data.remote.CustomerDto
import com.tinhcd.myesalessfa.data.remote.RouteCustomerDto
import com.tinhcd.myesalessfa.data.remote.VisitDto
import com.tinhcd.myesalessfa.domain.DataResult
import com.tinhcd.myesalessfa.domain.model.Customer
import com.tinhcd.myesalessfa.domain.model.RouteStop
import com.tinhcd.myesalessfa.domain.model.VisitStatus
import com.tinhcd.myesalessfa.domain.repository.RouteRepository
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Columns
import java.time.LocalDate
import java.time.OffsetDateTime
import javax.inject.Inject
import javax.inject.Singleton

private const val ROUTE_COLUMNS =
    "visit_order,customer:customer_id(id,code,name,address,phone,lat,lng,avatar_url,checkin_radius_m)"

@Singleton
class RouteRepositoryImpl @Inject constructor(
    private val client: SupabaseClient,
) : RouteRepository {

    /**
     * Two round trips rather than one clever join: the stops for the weekday,
     * and today's visit rows. RLS already limits both to the signed-in rep, so
     * neither query carries a branch or salesperson filter — that is the whole
     * point of pushing scoping into the database.
     */
    override suspend fun getRoute(date: LocalDate): DataResult<List<RouteStop>> = try {
        val weekday = date.dayOfWeek.value // ISO: Monday = 1

        val stops = client.from("route_customer")
            .select(Columns.raw(ROUTE_COLUMNS)) {
                filter {
                    eq("is_active", true)
                    contains("visit_weekdays", listOf(weekday))
                }
                order("visit_order", io.github.jan.supabase.postgrest.query.Order.ASCENDING)
            }
            .decodeList<RouteCustomerDto>()

        val visits = client.from("visit")
            .select(Columns.raw("id,customer_id,status,check_in_at,check_out_at")) {
                filter { eq("visit_date", date.toString()) }
            }
            .decodeList<VisitDto>()
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
