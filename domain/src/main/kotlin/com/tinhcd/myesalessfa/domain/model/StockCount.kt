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
) {
    val baseQty: Int get() = qty * conversionRate

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
) {
    val countedProducts: Int get() = lines.size

    val outOfStockCount: Int get() = lines.count { it.baseQty == 0 }

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
