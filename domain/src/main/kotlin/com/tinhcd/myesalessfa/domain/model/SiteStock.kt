package com.tinhcd.myesalessfa.domain.model

import com.tinhcd.myesalessfa.domain.foldForSearch

/** A warehouse orders are issued from. */
data class Site(
    val siteId: String,
    val code: String,
    val name: String,
    val address: String?,
)

/**
 * One product's stock at a warehouse, in base units.
 *
 * [updatedAtEpochMs] travels with the quantity rather than being a property of
 * the screen: warehouses do not all report at the same time, and one line being
 * three days old while the rest are an hour old is exactly what a rep needs to
 * see before promising anything.
 */
data class SiteStockItem(
    val productId: String,
    val productCode: String,
    val productName: String,
    val baseUom: String,
    val qtyBase: Int,
    val updatedAtEpochMs: Long?,
) {
    val isOutOfStock: Boolean get() = qtyBase <= 0
}

/**
 * What the warehouse screen is showing.
 *
 * The search runs over code and name because a rep looks a product up whichever
 * they happen to remember, and diacritics are folded for the reason the route
 * search folds them — nobody reaches for the tone keys one-handed.
 */
data class SiteStockView(
    val sites: List<Site> = emptyList(),
    val siteId: String? = null,
    val items: List<SiteStockItem> = emptyList(),
    val query: String = "",
) {
    val site: Site? get() = sites.firstOrNull { it.siteId == siteId }

    val visible: List<SiteStockItem>
        get() {
            val needle = query.trim().foldForSearch()
            if (needle.isEmpty()) return items
            return items.filter {
                it.productName.foldForSearch().contains(needle) ||
                    it.productCode.foldForSearch().contains(needle)
            }
        }

    val outOfStockCount: Int get() = items.count { it.isOutOfStock }

    /**
     * The oldest line, which is what the freshness note should quote.
     *
     * Using the newest would let one line updated a minute ago vouch for a list
     * where everything else is a week stale.
     */
    val oldestUpdateEpochMs: Long? get() = items.mapNotNull { it.updatedAtEpochMs }.minOrNull()
}
