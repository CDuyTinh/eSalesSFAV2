package com.tinhcd.myesalessfa.domain.model

/**
 * Counting what is on the outlet's shelves, which is what an order should be
 * built from.
 *
 * The count is only worth taking if the rep can see it against the last one, so
 * every line carries the previous figure and the two together say what sold. All
 * comparison happens in base units: the same product gets counted loose one week
 * and by the case the next, and 2 cases versus 30 pieces is not a comparison
 * anyone can make in their head.
 */
data class StockCountLine(
    val productId: String,
    val productCode: String,
    val productName: String,
    val uomCode: String,
    val uomName: String,
    val conversionRate: Int,
    /**
     * Units counted, in [uomCode]. Zero is a real answer — "I looked and there
     * were none" is an out-of-stock report, which is one of the more useful
     * things a rep brings back, and quite different from not having looked.
     */
    val qty: Int,
    /** This product's total at the customer's previous count, in base units. */
    val prevBaseQty: Int,
    /**
     * Par level from the outlet's must-stock list, or null when this SKU is not on
     * it. Base units, like everything else that compares against a count.
     */
    val mslMinBaseQty: Int? = null,
) {
    val baseQty: Int get() = qty * conversionRate

    val isMustStock: Boolean get() = mslMinBaseQty != null

    /**
     * Base units short of the must-stock minimum — the replenishment figure.
     *
     * Zero for a SKU that is not on the list, and zero when the shelf already
     * holds enough: a par level is a floor, not a target to top up to exactly, and
     * telling a rep to order more of something already sufficient would train them
     * to ignore the number.
     */
    val shortfallBaseQty: Int
        get() = mslMinBaseQty?.let { (it - baseQty).coerceAtLeast(0) } ?: 0

    /**
     * Base units that left the shelf since the previous count.
     *
     * Clamped at zero: a rise means the outlet was restocked from somewhere else
     * between visits, which happens, and reporting it as negative sales would put
     * a number in front of the rep that reads like a mistake on their part.
     */
    val soldSinceCount: Int get() = (prevBaseQty - baseQty).coerceAtLeast(0)

    /** True when the shelf is empty and was not empty last time. */
    val isNewlyOutOfStock: Boolean get() = baseQty == 0 && prevBaseQty > 0
}

data class DraftStockCount(
    val visitId: String,
    val customerId: String,
    val lines: List<StockCountLine> = emptyList(),
    val note: String = "",
    /**
     * Product id -> par level for this outlet, resolved from its must-stock lists.
     * Held whole rather than only on the lines, because the interesting question is
     * about SKUs that have *no* line yet.
     */
    val mustStock: Map<String, Int> = emptyMap(),
) {
    val countedProducts: Int get() = lines.size

    val outOfStockCount: Int get() = lines.count { it.baseQty == 0 }

    /** Must-stock SKUs the rep has not looked at yet. */
    val uncheckedMustStock: Set<String>
        get() = mustStock.keys - lines.mapTo(mutableSetOf()) { it.productId }

    /**
     * The outlet's standing against its list. Derived from the count rather than
     * recorded separately: the count already says how much of each product was on
     * the shelf, and a second copy of that fact would drift from the first.
     */
    val compliance: MslCompliance
        get() {
            val mustStockLines = lines.filter { it.productId in mustStock }
            return MslCompliance(
                required = mustStock.size,
                available = mustStockLines.count { it.baseQty > 0 },
                outOfStock = mustStockLines.count { it.baseQty == 0 },
                unchecked = uncheckedMustStock.size,
            )
        }

    /** Total base units needed to bring every counted must-stock SKU up to par. */
    val totalShortfallBaseQty: Int get() = lines.sumOf { it.shortfallBaseQty }

    /**
     * A count with nothing in it is not a count. Note that a count made up
     * entirely of zeroes *is* submittable — an outlet that has sold out of
     * everything the rep checked is exactly what head office needs to hear.
     */
    val canSubmit: Boolean get() = lines.isNotEmpty()

    /**
     * Records [line], replacing any earlier entry for the same product and unit,
     * matching the server's unique (count, product, unit).
     */
    fun withLine(line: StockCountLine): DraftStockCount {
        val without = lines.filterNot {
            it.productId == line.productId && it.uomCode == line.uomCode
        }
        return copy(lines = without + line)
    }

    /**
     * Removes the entry entirely — the rep did not check this product. Distinct
     * from recording zero, which says they did and it was empty.
     */
    fun withoutLine(productId: String, uomCode: String): DraftStockCount = copy(
        lines = lines.filterNot { it.productId == productId && it.uomCode == uomCode },
    )

    fun lineFor(productId: String, uomCode: String): StockCountLine? =
        lines.firstOrNull { it.productId == productId && it.uomCode == uomCode }
}
