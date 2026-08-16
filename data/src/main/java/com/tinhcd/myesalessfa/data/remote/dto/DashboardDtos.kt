package com.tinhcd.myesalessfa.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Payload of `/dashboard`.
 *
 * Assembled by `dashboard_overview()` in one read, so the orders total and the
 * visit count on screen always describe the same instant.
 *
 * The target fields are the only nullable ones here, and deliberately so: null
 * means head office has set no target for the month, which the screen says out
 * loud rather than drawing a bar against zero.
 */

@Serializable
data class DashboardDto(
    val date: String,
    val today: DashboardTodayDto = DashboardTodayDto(),
    val month: DashboardMonthDto = DashboardMonthDto(),
    val charts: DashboardChartsDto = DashboardChartsDto(),
)

@Serializable
data class DashboardTodayDto(
    val revenue: Long = 0,
    @SerialName("order_count") val orderCount: Int = 0,
    @SerialName("visit_done") val visitDone: Int = 0,
    @SerialName("visit_planned") val visitPlanned: Int = 0,
    @SerialName("sku_per_order") val skuPerOrder: Double = 0.0,
)

@Serializable
data class DashboardMonthDto(
    val revenue: Long = 0,
    @SerialName("revenue_target") val revenueTarget: Long? = null,
    @SerialName("order_count") val orderCount: Int = 0,
    @SerialName("order_target") val orderTarget: Int? = null,
)

@Serializable
data class DashboardChartsDto(
    @SerialName("this_week") val thisWeek: List<SalesPointDto> = emptyList(),
    @SerialName("last_week") val lastWeek: List<SalesPointDto> = emptyList(),
    @SerialName("this_month") val thisMonth: List<SalesPointDto> = emptyList(),
)

@Serializable
data class SalesPointDto(
    val title: String,
    val actual: Long = 0,
)
