package com.tinhcd.myesalessfa.data.repository

import com.tinhcd.myesalessfa.data.local.OutboxDao
import com.tinhcd.myesalessfa.data.local.OutboxEntity
import com.tinhcd.myesalessfa.data.local.RouteCacheEntity
import com.tinhcd.myesalessfa.data.local.RouteDao
import com.tinhcd.myesalessfa.data.remote.CustomerDto
import com.tinhcd.myesalessfa.data.remote.FunctionsService
import com.tinhcd.myesalessfa.data.remote.NewVisitDto
import com.tinhcd.myesalessfa.data.remote.RouteDto
import com.tinhcd.myesalessfa.data.remote.RouteStopDto
import com.tinhcd.myesalessfa.domain.DataResult
import com.tinhcd.myesalessfa.domain.model.Customer
import com.tinhcd.myesalessfa.domain.model.DayRoute
import com.tinhcd.myesalessfa.domain.model.RouteStop
import com.tinhcd.myesalessfa.domain.model.VisitStatus
import com.tinhcd.myesalessfa.domain.repository.RouteRepository
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.time.OffsetDateTime
import javax.inject.Inject
import javax.inject.Singleton

private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/** Yesterday and today are worth keeping; the week before last is not. */
private const val CACHE_DAYS_KEPT = 3L

@Singleton
class RouteRepositoryImpl @Inject constructor(
    private val service: FunctionsService,
    private val routeDao: RouteDao,
    private val outboxDao: OutboxDao,
) : RouteRepository {

    /**
     * One call, then kept.
     *
     * The request itself replaced two round trips and a client-side join — the stops
     * for the weekday, today's visit rows, matched by customer id — and the function
     * does that join next to the data, deriving the weekday from the date so the two
     * can no longer disagree. Still no branch or salesperson filter anywhere: RLS
     * scopes it, on both sides of the function boundary.
     *
     * What is new is that the answer is written to the device. Everything the rep
     * *writes* was built to survive being offline, but the screen that lists the
     * customers needed a live connection, so a rep in a shop with no bars got an error
     * and could not even see which outlet they were in. A route is small, changes once
     * a day, and is exactly the sort of thing a phone should already know.
     */
    override suspend fun getRoute(date: LocalDate): DataResult<DayRoute> {
        val key = date.toString()

        return try {
            val fresh = service.route(key)
            val fetchedAt = System.currentTimeMillis()

            routeDao.upsert(
                RouteCacheEntity(date = key, json = json.encodeToString(fresh), fetchedAt = fetchedAt),
            )
            routeDao.deleteBefore(date.minusDays(CACHE_DAYS_KEPT).toString())

            DataResult.Success(
                DayRoute(
                    stops = fresh.stops.map { it.toDomain() }.withPendingCheckIns(key),
                    fetchedAtEpochMs = fetchedAt,
                    fromCache = false,
                ),
            )
        } catch (e: Exception) {
            // A date nobody has ever fetched genuinely cannot be answered, and saying
            // so beats showing an empty route that looks like a day off.
            val cached = routeDao.forDate(key)
                ?: return DataResult.Failure(e.toAppError())

            DataResult.Success(
                DayRoute(
                    stops = json.decodeFromString<RouteDto>(cached.json)
                        .stops.map { it.toDomain() }
                        .withPendingCheckIns(key),
                    fetchedAtEpochMs = cached.fetchedAt,
                    fromCache = true,
                ),
            )
        }
    }

    override suspend fun getStop(customerId: String, date: LocalDate): DataResult<RouteStop?> =
        when (val all = getRoute(date)) {
            is DataResult.Success ->
                DataResult.Success(all.data.stops.firstOrNull { it.customer.id == customerId })

            is DataResult.Failure -> all
        }

    /**
     * Marks the stops whose check-in is still sitting in the outbox.
     *
     * Applied to a fresh route as well as a cached one. A check-in queued a moment ago
     * has not reached the server, so the server's own answer still says "not visited" —
     * and without this the rep would be invited to check in a second time. `visit` is
     * unique on (customer, salesperson, date), so that second attempt would be refused
     * by the database and then retried out of the queue for ever.
     */
    private suspend fun List<RouteStop>.withPendingCheckIns(date: String): List<RouteStop> {
        val pending = outboxDao.payloadsOfType(OutboxEntity.TYPE_CHECK_IN)
            .mapNotNull { runCatching { json.decodeFromString<NewVisitDto>(it) }.getOrNull() }
            .filter { it.visitDate == date }
            .map { it.customerId }
            .toSet()

        if (pending.isEmpty()) return this
        return map { stop ->
            if (stop.customer.id in pending) stop.copy(checkInPending = true) else stop
        }
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
