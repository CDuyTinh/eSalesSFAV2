package com.tinhcd.myesalessfa.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** The list, its counts, and the photo slots the edit form needs. */
@Serializable
data class CustomerRegistrationBoardDto(
    val total: Int = 0,
    val counts: Map<String, Int> = emptyMap(),
    val items: List<CustomerRegistrationRowDto> = emptyList(),
    @SerialName("photo_types") val photoTypes: List<CustomerPhotoTypeDto> = emptyList(),
)

@Serializable
data class CustomerPhotoTypeDto(
    val id: String,
    val code: String,
    val name: String,
    @SerialName("sample_url") val sampleUrl: String? = null,
    @SerialName("is_required") val isRequired: Boolean = false,
    @SerialName("is_avatar") val isAvatar: Boolean = false,
)

@Serializable
data class CustomerRegistrationRowDto(
    val id: String,
    val code: String = "",
    val name: String = "",
    val phone: String? = null,
    val address: String? = null,
    val status: String,
    val note: String? = null,
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("avatar_url") val avatarUrl: String? = null,
    @SerialName("photo_count") val photoCount: Int = 0,
)

@Serializable
data class CustomerRegistrationDetailDto(
    val id: String,
    val code: String = "",
    val name: String = "",
    val phone: String? = null,
    val address: String? = null,
    @SerialName("contact_name") val contactName: String? = null,
    val status: String,
    val note: String? = null,
    @SerialName("created_at") val createdAt: String = "",
    val lat: Double? = null,
    val lng: Double? = null,
    @SerialName("avatar_url") val avatarUrl: String? = null,
    @SerialName("province_id") val provinceId: String? = null,
    @SerialName("district_id") val districtId: String? = null,
    @SerialName("ward_id") val wardId: String? = null,
    @SerialName("class_id") val classId: String? = null,
    @SerialName("channel_id") val channelId: String? = null,
    @SerialName("shop_type_id") val shopTypeId: String? = null,
    val photos: List<CustomerRegistrationPhotoDto> = emptyList(),
)

@Serializable
data class CustomerRegistrationPhotoDto(
    val id: String,
    @SerialName("type_id") val typeId: String,
    @SerialName("type_code") val typeCode: String = "",
    @SerialName("type_name") val typeName: String = "",
    @SerialName("storage_path") val storagePath: String,
    /** A signed link, good for an hour. Null when signing failed. */
    val url: String? = null,
    @SerialName("taken_at") val takenAt: String? = null,
)

/** What the three writes hand back: the registration as it now stands, or nothing. */
@Serializable
data class CustomerRegistrationAckDto(
    val registration: CustomerRegistrationDetailDto? = null,
)

/** The detail endpoint wraps its one row, the same as the writes do. */
@Serializable
data class CustomerRegistrationDetailWrapper(
    val registration: CustomerRegistrationDetailDto,
)

/**
 * The three moves on a filed registration, in one body.
 *
 * [registration] is passed straight to `update_customer_registration` as jsonb,
 * so its field names are the function's own rather than this layer's.
 */
@Serializable
data class CustomerRegistrationCommandPayload(
    val action: String,
    val registration: JsonElement? = null,
    val customerId: String? = null,
    val typeId: String? = null,
    val storagePath: String? = null,
    val takenAt: String? = null,
    val lat: Double? = null,
    val lng: Double? = null,
    val fileSize: Int? = null,
)
