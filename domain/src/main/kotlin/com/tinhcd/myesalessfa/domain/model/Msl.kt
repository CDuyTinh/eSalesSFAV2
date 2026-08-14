package com.tinhcd.myesalessfa.domain.model

import java.time.LocalDate

/**
 * Must-stock lists: which SKUs are supposed to be on an outlet's shelf, and how
 * much of each.
 *
 * This is what stock counting was missing. A count says what is there; without a
 * par level to compare against, nothing says what should have been. The gap
 * between the two is the replenishment figure the legacy schema calls
 * `suggest_qty`, and it could not be computed until this existed.
 */

/** One SKU's obligation, in base units. */
data class MslItem(
    val productId: String,
    /** The shelf should hold at least this much, counted in base units. */
    val minBaseQty: Int,
)

/**
 * A list as head office defined it, scoped by channel and shop type.
 *
 * A null [channelId] or [shopTypeId] means "any", which is how a national core
 * list is expressed without enumerating every combination.
 */
data class MslDefinition(
    val id: String,
    val code: String,
    val channelId: String?,
    val shopTypeId: String?,
    val fromDate: LocalDate,
    val toDate: LocalDate,
    val items: List<MslItem>,
)

/**
 * The obligations that apply to one outlet on one day, as product id -> par level.
 *
 * Lists are **unioned**, not chosen between, and where two demand the same product
 * the stricter par level wins. This is the one place where MSL deliberately
 * differs from pricing: a product has exactly one price, so the most specific rule
 * has to win there. A must-stock list is a set of obligations, so adding a
 * channel-specific list must not silently discard the national one — and two lists
 * both demanding a product means it is required, at whichever figure is higher.
 *
 * A customer with no channel picks up only the lists that name no channel, the
 * same asymmetry as the price lookup: an unclassified outlet must not inherit
 * obligations written for a segment it is not in.
 */
fun List<MslDefinition>.mslFor(
    channelId: String?,
    shopTypeId: String?,
    on: LocalDate,
): Map<String, Int> {
    val par = mutableMapOf<String, Int>()
    for (list in this) {
        val applies = (list.channelId == null || list.channelId == channelId) &&
            (list.shopTypeId == null || list.shopTypeId == shopTypeId) &&
            !on.isBefore(list.fromDate) &&
            !on.isAfter(list.toDate)
        if (!applies) continue

        for (item in list.items) {
            val existing = par[item.productId]
            if (existing == null || item.minBaseQty > existing) {
                par[item.productId] = item.minBaseQty
            }
        }
    }
    return par
}

/**
 * How an outlet measured up against its must-stock list.
 *
 * `unchecked` is the reason the count distinguishes an absent line from a zero
 * one. A SKU nobody looked at is not evidence of anything, and reporting it as
 * out-of-stock would put the rep's omission on the outlet's record.
 */
data class MslCompliance(
    val required: Int,
    val available: Int,
    val outOfStock: Int,
    val unchecked: Int,
) {
    /** Percent of required SKUs found on the shelf, of those actually checked. */
    val availabilityPercent: Int
        get() {
            val checked = available + outOfStock
            return if (checked == 0) 0 else (available * 100) / checked
        }

    val isComplete: Boolean get() = unchecked == 0
}
