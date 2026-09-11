package com.tinhcd.myesalessfa.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** The board: list, counts, calendar and catalogue in one response. */
@Serializable
data class WorkPlanBoardDto(
    val total: Int = 0,
    /** Keyed by the status wire name; absent statuses simply have no entry. */
    val counts: Map<String, Int> = emptyMap(),
    val items: List<WorkPlanDto> = emptyList(),
    val calendar: List<WorkPlanDayDto> = emptyList(),
    val categories: List<WorkWithCategoryDto> = emptyList(),
)

@Serializable
data class WorkWithCategoryDto(
    val id: String,
    val code: String,
    val name: String,
    @SerialName("field_training") val fieldTraining: Boolean = false,
    @SerialName("trade_program") val tradeProgram: Boolean = false,
    @SerialName("object_kind") val objectKind: String? = null,
    @SerialName("min_sales") val minSales: Long? = null,
    @SerialName("max_sales") val maxSales: Long? = null,
)

@Serializable
data class WorkPlanDto(
    val id: String,
    @SerialName("category_id") val categoryId: String,
    @SerialName("category_code") val categoryCode: String = "",
    @SerialName("category_name") val categoryName: String = "",
    @SerialName("field_training") val fieldTraining: Boolean = false,
    @SerialName("object_kind") val objectKind: String? = null,
    val content: String = "",
    val note: String? = null,
    val place: String? = null,
    @SerialName("from_at") val fromAt: String,
    @SerialName("to_at") val toAt: String,
    val status: String,
    val result: String? = null,
    val reason: String? = null,
    @SerialName("assigned_to_me") val assignedToMe: Boolean = false,
    @SerialName("object_count") val objectCount: Int = 0,
)

@Serializable
data class WorkPlanDayDto(
    val day: String,
    val status: String,
    @SerialName("plan_count") val planCount: Int = 0,
)

/** One plan opened on its own. The list fields plus what hangs off it. */
@Serializable
data class WorkPlanDetailDto(
    val id: String,
    @SerialName("category_id") val categoryId: String,
    @SerialName("category_code") val categoryCode: String = "",
    @SerialName("category_name") val categoryName: String = "",
    @SerialName("field_training") val fieldTraining: Boolean = false,
    @SerialName("trade_program") val tradeProgram: Boolean = false,
    @SerialName("object_kind") val objectKind: String? = null,
    val content: String = "",
    val note: String? = null,
    val place: String? = null,
    @SerialName("from_at") val fromAt: String,
    @SerialName("to_at") val toAt: String,
    val status: String,
    val result: String? = null,
    val reason: String? = null,
    @SerialName("assigned_to_me") val assignedToMe: Boolean = false,
    @SerialName("submitted_at") val submittedAt: String? = null,
    @SerialName("approved_at") val approvedAt: String? = null,
    @SerialName("rejected_at") val rejectedAt: String? = null,
    @SerialName("completed_at") val completedAt: String? = null,
    @SerialName("cancelled_at") val cancelledAt: String? = null,
    val objects: List<WorkPlanObjectDto> = emptyList(),
    val history: List<WorkPlanEventDto> = emptyList(),
    val photos: List<WorkPlanPhotoDto> = emptyList(),
)

@Serializable
data class WorkPlanObjectDto(
    val id: String,
    val kind: String,
    @SerialName("target_id") val targetId: String,
    val name: String? = null,
    val code: String? = null,
    val address: String? = null,
)

@Serializable
data class WorkPlanEventDto(
    val status: String,
    val at: String,
    val actor: String? = null,
    val reason: String? = null,
)

@Serializable
data class WorkPlanPhotoDto(
    val id: String,
    @SerialName("storage_path") val storagePath: String,
    @SerialName("taken_at") val takenAt: String? = null,
)

/** What save/status/delete hand back: the plan as it now stands, or nothing. */
@Serializable
data class WorkPlanWriteAckDto(
    val plan: WorkPlanDetailDto? = null,
)

// -----------------------------------------------------------------------------

/**
 * The three moves on a plan, in one body.
 *
 * [plan] is passed straight to `save_work_plan` as jsonb, so its field names are
 * the function's own rather than this layer's.
 */
@Serializable
data class WorkPlanCommandPayload(
    val action: String,
    val plan: JsonElement? = null,
    val planId: String? = null,
    val status: String? = null,
    val reason: String? = null,
)

/** The object picker's universe: the outlets on this rep's routes. */
@Serializable
data class PlannableCustomersDto(
    val customers: List<PlannableCustomerDto> = emptyList(),
)

@Serializable
data class PlannableCustomerDto(
    val id: String,
    val code: String,
    val name: String,
    val address: String? = null,
)
