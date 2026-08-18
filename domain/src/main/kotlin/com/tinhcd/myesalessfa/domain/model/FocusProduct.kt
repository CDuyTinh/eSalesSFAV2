package com.tinhcd.myesalessfa.domain.model

import java.time.LocalDate

/**
 * One SKU head office is pushing this period, and how far this rep has got.
 *
 * [soldBaseQty] and [outlets] are the rep's own, because `sales_order` is scoped
 * to them — so this is a personal scoreboard, not the branch's.
 */
data class FocusProduct(
    val focusId: String,
    val productId: String,
    val productCode: String,
    val productName: String,
    val baseUom: String,
    val fromDate: LocalDate,
    val toDate: LocalDate,
    val priority: Int,
    /**
     * Null when the push is qualitative — get it on the shelf — which is a real
     * instruction and not the same as a target of zero.
     */
    val targetBaseQty: Int?,
    val note: String?,
    val soldBaseQty: Int,
    /** Distinct outlets that have taken it. Quantity alone can hide poor reach. */
    val outlets: Int,
) {
    /** 0f..1f for a bar, or null when there is no target to progress against. */
    val progress: Float?
        get() = targetBaseQty
            ?.takeIf { it > 0 }
            ?.let { (soldBaseQty.toFloat() / it).coerceIn(0f, 1f) }

    /** The real attainment, which the bar has to clamp and the rep should not. */
    val percent: Int?
        get() = targetBaseQty
            ?.takeIf { it > 0 }
            ?.let { Math.round(soldBaseQty.toDouble() / it * 100).toInt() }

    fun remaining(): Int? = targetBaseQty?.let { (it - soldBaseQty).coerceAtLeast(0) }

    /**
     * Days left including today, so the last day of a push reads as "1 ngày" and
     * not as "0" — a rep still has that day to sell in.
     */
    fun daysLeft(today: LocalDate): Long =
        (toDate.toEpochDay() - today.toEpochDay() + 1).coerceAtLeast(0)

    fun isEndingSoon(today: LocalDate): Boolean = daysLeft(today) in 1..3
}
