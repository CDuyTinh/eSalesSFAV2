package com.tinhcd.myesalessfa.domain.model

/** The signed-in field rep, resolved from the auth session. */
data class Salesperson(
    val id: String,
    val code: String,
    val fullName: String,
    val branchId: String,
    val branchCode: String,
    val branchName: String,
)

data class Customer(
    val id: String,
    val code: String,
    val name: String,
    val address: String?,
    val phone: String?,
    val lat: Double?,
    val lng: Double?,
    val avatarUrl: String?,
    /** Null means "fall back to the global gps_checkin_radius_m setting". */
    val checkInRadiusM: Int?,
    /**
     * Customer class, which is what the price list is grouped by. Null means the
     * outlet pays list price.
     */
    val classId: String? = null,
    /**
     * Segment the must-stock lists are scoped by. Null on either means the outlet
     * only picks up lists that name no channel / no shop type.
     */
    val channelId: String? = null,
    val shopTypeId: String? = null,
)

enum class VisitStatus {
    PLANNED,
    IN_PROGRESS,
    COMPLETED,
    NO_ORDER,

    /** The outlet was shut when the rep arrived — reported by the rep. */
    CLOSED,

    /**
     * Checked into on an earlier day and never checked out of, so the server closed
     * it. Not the same as [CLOSED], which is a fact about the shop; this is a fact
     * about the visit. A rep cannot reach one of these — a previous day's visit is
     * not resumable, because checking out of it now would stamp a time long after
     * the rep left.
     */
    ABANDONED,
}

/** One stop on today's route: the customer plus how the visit is going. */
data class RouteStop(
    val customer: Customer,
    val visitOrder: Int,
    val status: VisitStatus,
    val visitId: String?,
    val checkInAtEpochMs: Long?,
    val checkOutAtEpochMs: Long?,
    /**
     * A check-in for this stop is sitting in the outbox, unsent.
     *
     * Kept apart from [status], which says what the server believes. The distinction
     * matters twice over: the rep must be able to see that they have already checked
     * in here, and they must not be offered the check-in again, because `visit` is
     * unique on (customer, salesperson, date) and a second one would be refused by the
     * database and then retried out of the queue for ever.
     */
    val checkInPending: Boolean = false,
) {
    /** Nothing more can be done here until the queued check-in reaches the server. */
    val isWaitingToSync: Boolean get() = checkInPending && visitId == null
}

/**
 * One day's stops, and how current they are.
 *
 * The freshness travels with the list rather than with each stop because it is a fact
 * about the fetch, not about a customer — and a rep looking at a route needs to know
 * whether they are seeing this morning's plan or a copy the phone kept from before
 * the signal went.
 */
data class DayRoute(
    val stops: List<RouteStop>,
    /** When the server last answered for this date. */
    val fetchedAtEpochMs: Long,
    /** The server could not be reached; these stops came off the device. */
    val fromCache: Boolean,
)

data class GeoPoint(
    val lat: Double,
    val lng: Double,
    val accuracyM: Float?,
)

/** Everything captured at the moment the rep checks in to an outlet. */
data class CheckInRequest(
    val customerId: String,
    val point: GeoPoint,
    val distanceM: Double,
    val photoPath: String?,
    val reasonCode: String?,
    val note: String?,
)
