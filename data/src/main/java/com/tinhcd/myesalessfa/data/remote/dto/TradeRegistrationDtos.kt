package com.tinhcd.myesalessfa.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** The registration list, its counts, its filters and the rep's slots. */
@Serializable
data class TradeRegistrationBoardDto(
    val total: Int = 0,
    val counts: Map<String, Int> = emptyMap(),
    @SerialName("kind_counts") val kindCounts: Map<String, Int> = emptyMap(),
    @SerialName("slots_total") val slotsTotal: Int = 0,
    @SerialName("slots_used") val slotsUsed: Int = 0,
    val items: List<TradeRegistrationDto> = emptyList(),
    val programs: List<TradeProgramRefDto> = emptyList(),
)

@Serializable
data class TradeRegistrationDto(
    val id: String,
    val kind: String,
    @SerialName("registered_at") val registeredAt: String = "",
    val status: String,
    val portion: Int = 1,
    @SerialName("customer_id") val customerId: String,
    @SerialName("customer_code") val customerCode: String = "",
    @SerialName("customer_name") val customerName: String = "",
    val phone: String? = null,
    val address: String? = null,
    @SerialName("avatar_url") val avatarUrl: String? = null,
    @SerialName("program_id") val programId: String,
    @SerialName("program_code") val programCode: String = "",
    @SerialName("program_name") val programName: String = "",
    @SerialName("level_name") val levelName: String? = null,
    val reason: String? = null,
)

@Serializable
data class TradeProgramRefDto(
    val id: String,
    val kind: String,
    val code: String = "",
    val name: String = "",
)

/** What one outlet may still join, both kinds in one response. */
@Serializable
data class TradeOffersDto(
    val display: List<DisplayProgramOfferDto> = emptyList(),
    val loyalty: List<LoyaltyOpenDto> = emptyList(),
)

/** The outlets this rep may sign up. */
@Serializable
data class RegisterableCustomersDto(
    val customers: List<RegisterableCustomerDto> = emptyList(),
)

@Serializable
data class RegisterableCustomerDto(
    val id: String,
    val code: String = "",
    val name: String = "",
    val address: String? = null,
)

/** One registration, made without a visit. */
@Serializable
data class TradeRegistrationPayload(
    val kind: String,
    val customerId: String,
    val programId: String,
    val levelId: String,
    val portion: Int = 1,
    val reason: String? = null,
)

@Serializable
data class TradeRegistrationAckDto(
    val id: String? = null,
)
