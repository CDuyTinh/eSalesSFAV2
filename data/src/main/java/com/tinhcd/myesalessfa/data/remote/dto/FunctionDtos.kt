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
    /** True for an outlet the rep registered themselves, not one from the MCP. */
    val unplanned: Boolean = false,
    /** Calls made on this shop today, the one described above included. */
    @SerialName("visit_count") val visitCount: Int = 0,
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
data class CustomerPurchasesDto(
    val months: Int = 3,
    val products: List<CustomerPurchaseDto> = emptyList(),
)

@Serializable
data class CustomerPurchaseDto(
    @SerialName("product_id") val productId: String,
    @SerialName("base_qty") val baseQty: Long = 0,
    @SerialName("order_count") val orderCount: Int = 0,
    @SerialName("avg_month_base_qty") val avgMonthBaseQty: Int = 0,
    @SerialName("last_order_date") val lastOrderDate: String? = null,
)

@Serializable
data class VisitCountDto(
    @SerialName("visit_id") val visitId: String,
    /** Null when this visit has not been counted yet. */
    @SerialName("count_date") val countDate: String? = null,
    /** Product id -> base-unit total, summed server-side across sale units. */
    val counted: Map<String, Int> = emptyMap(),
)

/**
 * The rep's selling day as the server sees it.
 *
 * Both timestamps null means the day has not been opened; only the second null
 * means it is under way. The client does not infer either from the absence of a
 * row, because "no rows came back" is also what a failed read looks like.
 */
@Serializable
data class WorkDayDto(
    @SerialName("work_date") val workDate: String,
    val branch: BranchDto,
    @SerialName("check_in_at") val checkInAt: String? = null,
    @SerialName("check_out_at") val checkOutAt: String? = null,
    /** Visits still in progress today, which is what blocks closing the day. */
    @SerialName("open_visits") val openVisits: Int = 0,
)

/** A punch at the depot. `type` is check_in or check_out; the server fixes nothing else. */
@Serializable
data class WorkDayPunchDto(
    val type: String,
    @SerialName("work_date") val workDate: String,
    @SerialName("happened_at") val happenedAt: String,
    val lat: Double? = null,
    val lng: Double? = null,
    @SerialName("accuracy_m") val accuracyM: Double? = null,
    @SerialName("distance_m") val distanceM: Double? = null,
    @SerialName("reason_id") val reasonId: String? = null,
)

@Serializable
data class NamedRefDto(
    val id: String,
    val code: String,
    val name: String,
)

/**
 * One shape for all three option calls. Which lists arrive depends on which
 * question was asked, so every field defaults to empty rather than being
 * required — a districts response carries no provinces and says nothing about
 * them.
 */
@Serializable
data class CustomerOptionsDto(
    val classes: List<NamedRefDto> = emptyList(),
    val channels: List<NamedRefDto> = emptyList(),
    @SerialName("shop_types") val shopTypes: List<NamedRefDto> = emptyList(),
    val provinces: List<NamedRefDto> = emptyList(),
    val districts: List<NamedRefDto> = emptyList(),
    val wards: List<NamedRefDto> = emptyList(),
)

/** Carries no code: the server assigns that, and sending one would invite a clash. */
@Serializable
data class NewCustomerDto(
    val name: String,
    val phone: String? = null,
    val address: String,
    @SerialName("ward_id") val wardId: String? = null,
    val lat: Double? = null,
    val lng: Double? = null,
    @SerialName("class_id") val classId: String? = null,
    @SerialName("channel_id") val channelId: String? = null,
    @SerialName("shop_type_id") val shopTypeId: String? = null,
    val note: String? = null,
)

@Serializable
data class RegisteredCustomerDto(
    val id: String,
    val code: String,
    val name: String,
)

@Serializable
data class RegisteredCustomerAckDto(
    val ok: Boolean = true,
    val customer: RegisteredCustomerDto,
)

// -----------------------------------------------------------------------------
// Reports
// -----------------------------------------------------------------------------

@Serializable
data class ActivitySummaryDto(
    val planned: Int = 0,
    val visited: Int = 0,
    val unplanned: Int = 0,
    val strike: Int = 0,
    @SerialName("non_strike") val nonStrike: Int = 0,
    val closed: Int = 0,
    @SerialName("order_amount") val orderAmount: Long = 0,
)

@Serializable
data class ActivityRowDto(
    @SerialName("visit_id") val visitId: String,
    @SerialName("customer_code") val customerCode: String,
    @SerialName("customer_name") val customerName: String,
    val address: String? = null,
    val planned: Boolean = true,
    val status: String = "planned",
    @SerialName("check_in_at") val checkInAt: String? = null,
    @SerialName("check_out_at") val checkOutAt: String? = null,
    val minutes: Int? = null,
    @SerialName("order_amount") val orderAmount: Long = 0,
)

@Serializable
data class ActivityReportDto(
    val date: String,
    val summary: ActivitySummaryDto = ActivitySummaryDto(),
    val rows: List<ActivityRowDto> = emptyList(),
)

@Serializable
data class CustomerSalesDto(
    @SerialName("customer_code") val customerCode: String,
    @SerialName("customer_name") val customerName: String,
    val orders: Int = 0,
    val revenue: Long = 0,
)

@Serializable
data class ProductSalesDto(
    @SerialName("product_code") val productCode: String,
    @SerialName("product_name") val productName: String,
    @SerialName("base_uom") val baseUom: String = "",
    @SerialName("base_qty") val baseQty: Int = 0,
    val revenue: Long = 0,
)

@Serializable
data class SalesReportDto(
    val month: String,
    val revenue: Long = 0,
    @SerialName("order_count") val orderCount: Int = 0,
    /** Absent when head office set none. Not the same as zero. */
    val target: Long? = null,
    val customers: List<CustomerSalesDto> = emptyList(),
    val products: List<ProductSalesDto> = emptyList(),
)

// -----------------------------------------------------------------------------
// Receivables
// -----------------------------------------------------------------------------

@Serializable
data class ReceivableCustomerDto(
    @SerialName("customer_id") val customerId: String,
    @SerialName("customer_code") val customerCode: String,
    @SerialName("customer_name") val customerName: String,
    val phone: String? = null,
    val address: String? = null,
    val invoices: Int = 0,
    val outstanding: Long = 0,
    val overdue: Boolean = false,
)

@Serializable
data class ReceivableCustomersDto(
    val customers: List<ReceivableCustomerDto> = emptyList(),
)

@Serializable
data class ReceivableInvoiceDto(
    @SerialName("invoice_id") val invoiceId: String,
    @SerialName("invoice_no") val invoiceNo: String,
    @SerialName("issued_on") val issuedOn: String,
    @SerialName("due_on") val dueOn: String,
    val total: Long = 0,
    val paid: Long = 0,
    val outstanding: Long = 0,
    val note: String? = null,
)

@Serializable
data class ReceivableInvoicesDto(
    val invoices: List<ReceivableInvoiceDto> = emptyList(),
)

/** The id is the client's, and is what makes a replayed save a no-op. */
@Serializable
data class PaymentAllocationDto(
    val id: String,
    @SerialName("invoice_id") val invoiceId: String,
    val amount: Long,
)

@Serializable
data class CollectPaymentDto(
    @SerialName("visit_id") val visitId: String? = null,
    @SerialName("collected_on") val collectedOn: String,
    val note: String? = null,
    val allocations: List<PaymentAllocationDto>,
)

/** Every write function answers with this, or with an error body carrying `message`. */
@Serializable
data class WriteAckDto(
    val ok: Boolean = true,
    @SerialName("order_id") val orderId: String? = null,
    @SerialName("stock_count_id") val stockCountId: String? = null,
)

// -----------------------------------------------------------------------------
// Daily sales targets
// -----------------------------------------------------------------------------

@Serializable
data class DailyTargetStopDto(
    @SerialName("customer_id") val customerId: String,
    @SerialName("customer_code") val customerCode: String,
    @SerialName("customer_name") val customerName: String,
    val address: String? = null,
    @SerialName("visit_order") val visitOrder: Int = 0,
    val target: Long = 0,
    @SerialName("has_target") val hasTarget: Boolean = false,
    /** Null when this rep has never sold here. */
    @SerialName("last_amount") val lastAmount: Long? = null,
    @SerialName("last_date") val lastDate: String? = null,
)

@Serializable
data class DailyTargetsDto(
    val date: String,
    val stops: List<DailyTargetStopDto> = emptyList(),
)

@Serializable
data class DailyTargetEntryDto(
    @SerialName("customer_id") val customerId: String,
    @SerialName("target_amount") val targetAmount: Long,
)

@Serializable
data class SaveDailyTargetsDto(
    val date: String,
    val targets: List<DailyTargetEntryDto>,
)

// -----------------------------------------------------------------------------
// Focus products
// -----------------------------------------------------------------------------

@Serializable
data class FocusProductDto(
    @SerialName("focus_id") val focusId: String,
    @SerialName("product_id") val productId: String,
    @SerialName("product_code") val productCode: String,
    @SerialName("product_name") val productName: String,
    @SerialName("base_uom") val baseUom: String = "",
    @SerialName("from_date") val fromDate: String,
    @SerialName("to_date") val toDate: String,
    val priority: Int = 0,
    /** Absent when the push is qualitative rather than a quantity. */
    @SerialName("target_base_qty") val targetBaseQty: Int? = null,
    val note: String? = null,
    @SerialName("sold_base_qty") val soldBaseQty: Int = 0,
    val outlets: Int = 0,
)

@Serializable
data class FocusProductsDto(
    val date: String,
    val products: List<FocusProductDto> = emptyList(),
)

// -----------------------------------------------------------------------------
// Issuing sites and their stock
// -----------------------------------------------------------------------------

@Serializable
data class SiteDto(
    @SerialName("site_id") val siteId: String,
    val code: String,
    val name: String,
    val address: String? = null,
)

@Serializable
data class SiteStockItemDto(
    @SerialName("product_id") val productId: String,
    @SerialName("product_code") val productCode: String,
    @SerialName("product_name") val productName: String,
    @SerialName("base_uom") val baseUom: String = "",
    @SerialName("qty_base") val qtyBase: Int = 0,
    @SerialName("updated_at") val updatedAt: String? = null,
)

@Serializable
data class SiteStockDto(
    val sites: List<SiteDto> = emptyList(),
    /** Null when the branch has no active warehouse at all. */
    @SerialName("site_id") val siteId: String? = null,
    val items: List<SiteStockItemDto> = emptyList(),
)

// -----------------------------------------------------------------------------
// Work notes
// -----------------------------------------------------------------------------

@Serializable
data class WorkNoteDto(
    @SerialName("note_id") val noteId: String,
    val title: String,
    val body: String? = null,
    @SerialName("due_on") val dueOn: String? = null,
    val status: String = "open",
    val result: String? = null,
    @SerialName("done_at") val doneAt: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("customer_id") val customerId: String? = null,
    @SerialName("customer_name") val customerName: String? = null,
)

@Serializable
data class WorkNotesDto(
    val notes: List<WorkNoteDto> = emptyList(),
)

@Serializable
data class NewWorkNoteDto(
    val title: String,
    val body: String? = null,
    @SerialName("due_on") val dueOn: String? = null,
    @SerialName("customer_id") val customerId: String? = null,
)

@Serializable
data class CompleteWorkNoteDto(
    @SerialName("note_id") val noteId: String,
    val result: String,
)

// -----------------------------------------------------------------------------
// Leave requests
// -----------------------------------------------------------------------------

@Serializable
data class LeaveTypeDto(
    @SerialName("leave_type_id") val leaveTypeId: String,
    val code: String,
    val name: String,
    @SerialName("is_paid") val isPaid: Boolean = true,
)

@Serializable
data class LeaveRequestDto(
    @SerialName("request_id") val requestId: String,
    @SerialName("leave_type_id") val leaveTypeId: String,
    @SerialName("type_name") val typeName: String,
    @SerialName("is_paid") val isPaid: Boolean = true,
    @SerialName("from_date") val fromDate: String,
    @SerialName("to_date") val toDate: String,
    val reason: String = "",
    val status: String = "pending",
    @SerialName("decision_note") val decisionNote: String? = null,
    @SerialName("decided_at") val decidedAt: String? = null,
)

@Serializable
data class LeaveBoardDto(
    val types: List<LeaveTypeDto> = emptyList(),
    val requests: List<LeaveRequestDto> = emptyList(),
)

@Serializable
data class NewLeaveRequestDto(
    @SerialName("leave_type_id") val leaveTypeId: String,
    @SerialName("from_date") val fromDate: String,
    @SerialName("to_date") val toDate: String,
    val reason: String,
)

@Serializable
data class WithdrawLeaveDto(
    @SerialName("request_id") val requestId: String,
)

// -----------------------------------------------------------------------------
// Customer detail
// -----------------------------------------------------------------------------

@Serializable
data class CustomerInfoDto(
    @SerialName("customer_id") val customerId: String,
    val code: String,
    val name: String,
    val phone: String? = null,
    val address: String? = null,
    @SerialName("avatar_url") val avatarUrl: String? = null,
    val lat: Double? = null,
    val lng: Double? = null,
    @SerialName("contact_name") val contactName: String? = null,
    @SerialName("channel_name") val channelName: String? = null,
    @SerialName("class_name") val className: String? = null,
    @SerialName("shop_type_name") val shopTypeName: String? = null,
    /** Nullable on purpose: absent means no limit set, which is not zero. */
    @SerialName("credit_limit") val creditLimit: Long? = null,
    @SerialName("month_revenue") val monthRevenue: Long = 0,
)

@Serializable
data class CustomerOrderLineDto(
    @SerialName("product_code") val productCode: String = "",
    @SerialName("product_name") val productName: String = "",
    @SerialName("uom_code") val uomCode: String = "",
    val qty: Int = 0,
    @SerialName("line_amount") val lineAmount: Long = 0,
)

@Serializable
data class CustomerOrderDto(
    @SerialName("order_id") val orderId: String,
    @SerialName("order_no") val orderNo: String = "",
    @SerialName("order_date") val orderDate: String,
    val status: String = "new",
    @SerialName("total_amount") val totalAmount: Long = 0,
    val lines: List<CustomerOrderLineDto> = emptyList(),
)

@Serializable
data class CustomerOrdersDto(
    val orders: List<CustomerOrderDto> = emptyList(),
)
