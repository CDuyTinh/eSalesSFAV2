package com.tinhcd.myesalessfa.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class BranchDto(
    val id: String,
    val code: String,
    val name: String,
)

@Serializable
data class SalespersonDto(
    val id: String,
    val code: String,
    @SerialName("full_name") val fullName: String,
    @SerialName("branch_id") val branchId: String,
    val branch: BranchDto? = null,
)

@Serializable
data class CustomerDto(
    val id: String,
    val code: String,
    val name: String,
    val address: String? = null,
    val phone: String? = null,
    val lat: Double? = null,
    val lng: Double? = null,
    @SerialName("avatar_url") val avatarUrl: String? = null,
    @SerialName("checkin_radius_m") val checkInRadiusM: Int? = null,
)

@Serializable
data class RouteCustomerDto(
    @SerialName("visit_order") val visitOrder: Int,
    val customer: CustomerDto,
)

@Serializable
data class VisitDto(
    val id: String,
    @SerialName("customer_id") val customerId: String,
    val status: String,
    @SerialName("check_in_at") val checkInAt: String? = null,
    @SerialName("check_out_at") val checkOutAt: String? = null,
)

@Serializable
data class SettingDto(
    val key: String,
    val value: String,
)

@Serializable
data class ReasonCodeDto(
    val id: String,
    val code: String,
    val name: String,
    val kind: String,
)

/** Payload for creating a visit row. Field names match the table columns. */
@Serializable
data class NewVisitDto(
    @SerialName("customer_id") val customerId: String,
    @SerialName("salesperson_id") val salespersonId: String,
    @SerialName("branch_id") val branchId: String,
    @SerialName("visit_date") val visitDate: String,
    val status: String,
    @SerialName("check_in_at") val checkInAt: String,
    @SerialName("check_in_lat") val checkInLat: Double,
    @SerialName("check_in_lng") val checkInLng: Double,
    @SerialName("check_in_accuracy_m") val checkInAccuracyM: Double? = null,
    @SerialName("check_in_distance_m") val checkInDistanceM: Double? = null,
    @SerialName("check_in_photo_path") val checkInPhotoPath: String? = null,
    val note: String? = null,
)
