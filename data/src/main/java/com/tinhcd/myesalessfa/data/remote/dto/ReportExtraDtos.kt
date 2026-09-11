package com.tinhcd.myesalessfa.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Báo cáo đơn hàng. */
@Serializable
data class OrderReportDto(
    val total: Int = 0,
    @SerialName("total_amount") val totalAmount: Long = 0,
    /** Keyed by the status wire name; absent statuses have no entry. */
    val counts: Map<String, Int> = emptyMap(),
    val items: List<OrderReportRowDto> = emptyList(),
)

@Serializable
data class OrderReportRowDto(
    val id: String,
    @SerialName("order_no") val orderNo: String = "",
    @SerialName("order_date") val orderDate: String = "",
    @SerialName("customer_code") val customerCode: String = "",
    @SerialName("customer_name") val customerName: String = "",
    val status: String,
    @SerialName("in_route") val inRoute: Boolean = false,
    @SerialName("line_count") val lineCount: Int = 0,
    @SerialName("sub_total") val subTotal: Long = 0,
    @SerialName("discount_amount") val discountAmount: Long = 0,
    @SerialName("vat_amount") val vatAmount: Long = 0,
    @SerialName("total_amount") val totalAmount: Long = 0,
)

/** Báo cáo SKU. */
@Serializable
data class SkuReportDto(
    val items: List<SkuReportRowDto> = emptyList(),
)

@Serializable
data class SkuReportRowDto(
    @SerialName("product_id") val productId: String,
    @SerialName("product_code") val productCode: String = "",
    @SerialName("product_name") val productName: String = "",
    @SerialName("stock_uom") val stockUom: String = "",
    @SerialName("qty_confirmed") val qtyConfirmed: Int = 0,
    @SerialName("qty_pending") val qtyPending: Int = 0,
    @SerialName("qty_total") val qtyTotal: Int = 0,
    @SerialName("amount_total") val amountTotal: Long = 0,
)

/** Báo cáo thương mại. */
@Serializable
data class TradeReportDto(
    @SerialName("display_count") val displayCount: Int = 0,
    @SerialName("loyalty_count") val loyaltyCount: Int = 0,
    @SerialName("customer_count") val customerCount: Int = 0,
    val items: List<TradeReportRowDto> = emptyList(),
)

@Serializable
data class TradeReportRowDto(
    val kind: String,
    @SerialName("registered_at") val registeredAt: String = "",
    val status: String = "",
    val portion: Int = 1,
    @SerialName("customer_code") val customerCode: String = "",
    @SerialName("customer_name") val customerName: String = "",
    @SerialName("program_code") val programCode: String = "",
    @SerialName("program_name") val programName: String = "",
    @SerialName("level_name") val levelName: String? = null,
)

/** Báo cáo kết quả công việc. */
@Serializable
data class WorkResultReportDto(
    val completed: Int = 0,
    val cancelled: Int = 0,
    val total: Int = 0,
    val items: List<WorkResultRowDto> = emptyList(),
)

@Serializable
data class WorkResultRowDto(
    val id: String,
    val content: String = "",
    @SerialName("category_name") val categoryName: String = "",
    val status: String,
    @SerialName("from_at") val fromAt: String = "",
    @SerialName("to_at") val toAt: String = "",
    @SerialName("object_count") val objectCount: Int = 0,
    val outcome: String? = null,
)
