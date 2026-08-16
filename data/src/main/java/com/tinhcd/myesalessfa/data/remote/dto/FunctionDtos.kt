package com.tinhcd.myesalessfa.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Payloads of the Edge Functions.
 *
 * These are shaped for the screens that consume them rather than mirroring
 * tables, which is the point of the functions existing: `/bootstrap` replaces five
 * round trips a rep waited through on the login screen, and `/catalogue` replaces
 * three unbounded table reads plus the grouping the client did afterwards.
 *
 * Settings and translations arrive as maps because they are only ever read by
 * key. The list-to-map step used to happen on the device on every refresh.
 */

@Serializable
data class SurveyOptionDto(
    val id: String,
    val code: String,
    val content: String,
    val score: Int = 0,
)

@Serializable
data class SurveyQuestionDto(
    val id: String,
    val code: String,
    val content: String,
    @SerialName("answer_type") val answerType: String,
    @SerialName("is_required") val isRequired: Boolean = true,
    val score: Int = 0,
    val options: List<SurveyOptionDto> = emptyList(),
)

@Serializable
data class SurveyGroupDto(
    val name: String,
    val questions: List<SurveyQuestionDto> = emptyList(),
)

/**
 * One questionnaire, nested and already sorted by the function — PostgREST does not
 * order embedded rows, and a questionnaire whose questions shuffle between screen
 * loads is one no rep can work through.
 */
@Serializable
data class SurveyTypeDto(
    val id: String,
    val code: String,
    val name: String,
    /** The workflow step this questionnaire belongs to. */
    @SerialName("form_id") val formId: String,
    @SerialName("pass_score") val passScore: Int = 0,
    val groups: List<SurveyGroupDto> = emptyList(),
)

@Serializable
data class BootstrapDto(
    /** Null when the auth user has no salesperson row — an unprovisioned account. */
    val salesperson: SalespersonDto? = null,
    val settings: Map<String, String> = emptyMap(),
    @SerialName("reason_codes") val reasonCodes: List<ReasonCodeDto> = emptyList(),
    @SerialName("sales_steps") val salesSteps: List<SalesStepDto> = emptyList(),
    val lang: String = "vi",
    val translations: Map<String, String> = emptyMap(),
    /** One per questionnaire step; cached whole and read whole. */
    val surveys: List<SurveyTypeDto> = emptyList(),
    /** Flat, with [MenuItemDto.parentCode] carrying the nesting. */
    val menu: List<MenuItemDto> = emptyList(),
)

@Serializable
data class MenuItemDto(
    val code: String,
    /** Null for a bottom-bar tab; set for an entry inside a tab's sheet. */
    @SerialName("parent_code") val parentCode: String? = null,
    @SerialName("title_key") val titleKey: String,
    @SerialName("sort_order") val sortOrder: Int = 0,
)

@Serializable
data class CatalogueUnitDto(
    @SerialName("uom_code") val uomCode: String,
    @SerialName("uom_name") val uomName: String,
    @SerialName("conversion_rate") val conversionRate: Int,
    @SerialName("is_default_sale") val isDefaultSale: Boolean = false,
    @SerialName("sort_order") val sortOrder: Int = 0,
)

@Serializable
data class CatalogueProductDto(
    val id: String,
    val code: String,
    val name: String,
    @SerialName("base_uom") val baseUom: String,
    @SerialName("vat_basis_points") val vatBasisPoints: Int,
    @SerialName("category_name") val categoryName: String? = null,
    @SerialName("category_sort") val categorySort: Int = 9999,
    /** Nested by the function, so no client-side grouping by product id. */
    val units: List<CatalogueUnitDto> = emptyList(),
)

@Serializable
data class MslItemDto(
    @SerialName("product_id") val productId: String,
    @SerialName("min_base_qty") val minBaseQty: Int,
)

@Serializable
data class MslDto(
    val id: String,
    val code: String,
    /** Null means the list applies to any channel. */
    @SerialName("channel_id") val channelId: String? = null,
    @SerialName("shop_type_id") val shopTypeId: String? = null,
    @SerialName("from_date") val fromDate: String,
    @SerialName("to_date") val toDate: String,
    val items: List<MslItemDto> = emptyList(),
)

@Serializable
data class CatalogueDto(
    @SerialName("generated_at") val generatedAt: String,
    val products: List<CatalogueProductDto> = emptyList(),
    /**
     * Rules, not resolved prices. A route holds customers in different classes, so
     * a price only exists relative to the outlet the rep is standing in.
     */
    @SerialName("price_rules") val priceRules: List<PriceListDto> = emptyList(),
    /** Unresolved for the same reason: scoped by the outlet's channel and shop type. */
    val msl: List<MslDto> = emptyList(),
)

@Serializable
data class RouteStopDto(
    @SerialName("visit_order") val visitOrder: Int,
    val customer: CustomerDto,
    /** Null until the rep has checked in. */
    @SerialName("visit_id") val visitId: String? = null,
    val status: String = "planned",
    @SerialName("check_in_at") val checkInAt: String? = null,
    @SerialName("check_out_at") val checkOutAt: String? = null,
)

@Serializable
data class RouteDto(
    val date: String,
    val stops: List<RouteStopDto> = emptyList(),
)

@Serializable
data class VisitWorkflowDto(
    @SerialName("visit_id") val visitId: String,
    val completions: List<VisitStepResultDto> = emptyList(),
)

@Serializable
data class PreviousCountDto(
    /**
     * Null when the outlet has never been counted, which is different from having
     * been counted and found empty — the rep needs to tell those apart.
     */
    @SerialName("count_date") val countDate: String? = null,
    /** Product id -> base-unit total, summed server-side across sale units. */
    val previous: Map<String, Int> = emptyMap(),
)

@Serializable
data class VisitCountDto(
    @SerialName("visit_id") val visitId: String,
    /** Null when this visit has not been counted yet. */
    @SerialName("count_date") val countDate: String? = null,
    /** Product id -> base-unit total, summed server-side across sale units. */
    val counted: Map<String, Int> = emptyMap(),
)

/** Every write function answers with this, or with an error body carrying `message`. */
@Serializable
data class WriteAckDto(
    val ok: Boolean = true,
    @SerialName("order_id") val orderId: String? = null,
    @SerialName("stock_count_id") val stockCountId: String? = null,
)
