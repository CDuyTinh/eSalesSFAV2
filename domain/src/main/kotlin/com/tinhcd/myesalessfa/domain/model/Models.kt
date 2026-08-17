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
     * The rep registered this outlet themselves and head office has not ruled on
     * it yet, so it is on today's list without being in any MCP route. Worth
     * saying on the card: the rep is looking at a stop nobody assigned them, and
     * an order taken there is against an outlet that could still be rejected.
     */
    val unplanned: Boolean = false,
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
