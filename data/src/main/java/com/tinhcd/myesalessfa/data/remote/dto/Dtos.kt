package com.tinhcd.myesalessfa.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * The last three are nullable because most callers do not ask for them: only the
 * work-day screen measures a distance to the depot, and the profile read that
 * fills the drawer has no use for its coordinates.
 */
@Serializable
data class BranchDto(
    val id: String,
    val code: String,
    val name: String,
    val address: String? = null,
    val lat: Double? = null,
    val lng: Double? = null,
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
    /** Drives which price list rows apply to this outlet. */
    @SerialName("class_id") val classId: String? = null,
    /** Both drive which must-stock lists apply. */
    @SerialName("channel_id") val channelId: String? = null,
    @SerialName("shop_type_id") val shopTypeId: String? = null,
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

@Serializable
data class SalesStepDto(
    @SerialName("form_id") val formId: String,
    val step: Int,
    @SerialName("title_key") val titleKey: String,
    @SerialName("is_required") val isRequired: Boolean,
    val config: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class TranslationDto(
    val key: String,
    val value: String,
)

// -----------------------------------------------------------------------------
// Product catalogue
// -----------------------------------------------------------------------------

@Serializable
data class ProductCategoryRefDto(
    val name: String,
    @SerialName("sort_order") val sortOrder: Int = 0,
)

@Serializable
data class ProductDto(
    val id: String,
    val code: String,
    val name: String,
    @SerialName("base_uom") val baseUom: String,
    @SerialName("vat_basis_points") val vatBasisPoints: Int,
    /** Embedded through the FK; null for an uncategorised product. */
    val category: ProductCategoryRefDto? = null,
)

@Serializable
data class UomRefDto(val name: String)

@Serializable
data class ProductUomDto(
    @SerialName("product_id") val productId: String,
    @SerialName("uom_code") val uomCode: String,
    @SerialName("conversion_rate") val conversionRate: Int,
    @SerialName("is_default_sale") val isDefaultSale: Boolean = false,
    @SerialName("sort_order") val sortOrder: Int = 0,
    val uom: UomRefDto? = null,
)

@Serializable
data class PriceListDto(
    @SerialName("product_id") val productId: String,
    @SerialName("uom_code") val uomCode: String,
    @SerialName("class_id") val classId: String? = null,
    val price: Long,
    @SerialName("from_date") val fromDate: String,
    @SerialName("to_date") val toDate: String,
)

@Serializable
data class VisitStepResultDto(
    @SerialName("form_id") val formId: String,
    @SerialName("completed_at") val completedAt: String,
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
