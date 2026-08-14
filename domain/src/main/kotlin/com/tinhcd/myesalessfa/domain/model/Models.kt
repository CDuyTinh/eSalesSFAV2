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
)

enum class VisitStatus { PLANNED, IN_PROGRESS, COMPLETED, NO_ORDER, CLOSED }

/** One stop on today's route: the customer plus how the visit is going. */
data class RouteStop(
    val customer: Customer,
    val visitOrder: Int,
    val status: VisitStatus,
    val visitId: String?,
    val checkInAtEpochMs: Long?,
    val checkOutAtEpochMs: Long?,
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
