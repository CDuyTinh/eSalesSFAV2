package com.tinhcd.myesalessfa.domain.model

/**
 * Turning a shelf shortfall into something orderable.
 *
 * The stock count says how far below par each required SKU is, in base units. An
 * order is written in sale units — cases, packs, pieces. Bridging the two is the
 * last step of the chain that started with must-stock lists, and it is the reason
 * a par level of 48 pieces can be acted on rather than merely reported.
 */
data class OrderSuggestion(
    val productId: String,
    val productCode: String,
    val productName: String,
    /** What the outlet is obliged to hold, in base units. */
    val parBaseQty: Int,
    /** What this visit's count found, in base units. */
    val countedBaseQty: Int,
    val uomCode: String,
    val uomName: String,
    val conversionRate: Int,
    /** Whole [uomCode] units to order. Advisory: the rep edits it. */
    val suggestedQty: Int,
    val unitPrice: Long,
) {
    val shortfallBaseQty: Int get() = (parBaseQty - countedBaseQty).coerceAtLeast(0)

    /**
     * Base units the suggestion overshoots par by, because sale units do not divide
     * the shortfall evenly. Worth showing: a rep asked to order a whole case to
     * cover a two-piece gap should be able to see that is what is happening.
     */
    val overshootBaseQty: Int
        get() = (suggestedQty * conversionRate - shortfallBaseQty).coerceAtLeast(0)
}

/**
 * Whole sale units needed to cover [shortfallBaseQty].
 *
 * Rounds up. Ordering a fraction of a case is not possible, and rounding down would
 * leave the shelf below the par level the whole exercise exists to restore. That
 * does mean a two-piece gap can suggest a full case — which is why the suggestion
 * is offered rather than applied, and why [OrderSuggestion.overshootBaseQty] makes
 * the rounding visible instead of burying it.
 *
 * A non-positive conversion rate would be corrupt catalogue data; treated as one
 * base unit per sale unit rather than dividing by zero mid-visit.
 */
fun suggestedSaleQty(shortfallBaseQty: Int, conversionRate: Int): Int {
    if (shortfallBaseQty <= 0) return 0
    val rate = if (conversionRate > 0) conversionRate else 1
    return (shortfallBaseQty + rate - 1) / rate
}

/**
 * Builds the suggestions for one visit.
 *
 * Only for SKUs the rep actually counted. A required product with no count is not
 * evidence the shelf is empty — nobody looked — and suggesting its full par would
 * put an order in front of a customer based on an omission. Those SKUs are reported
 * as uncounted elsewhere, which is a different and more honest message.
 *
 * A product priced at zero units, or absent from the catalogue, produces no
 * suggestion: the server refuses to book an unpriced line, so offering one would
 * only fail after the rep had told the customer it was on its way.
 */
fun orderSuggestions(
    mustStock: Map<String, Int>,
    countedBaseQty: Map<String, Int>,
    catalogue: List<PricedProduct>,
): List<OrderSuggestion> {
    val byProductId = catalogue.associateBy { it.product.id }

    return mustStock.mapNotNull { (productId, parBaseQty) ->
        val counted = countedBaseQty[productId] ?: return@mapNotNull null
        val shortfall = parBaseQty - counted
        if (shortfall <= 0) return@mapNotNull null

        val priced = byProductId[productId] ?: return@mapNotNull null
        val unit = priced.defaultUnit

        OrderSuggestion(
            productId = productId,
            productCode = priced.product.code,
            productName = priced.product.name,
            parBaseQty = parBaseQty,
            countedBaseQty = counted,
            uomCode = unit.unit.uomCode,
            uomName = unit.unit.uomName,
            conversionRate = unit.unit.conversionRate,
            suggestedQty = suggestedSaleQty(shortfall, unit.unit.conversionRate),
            unitPrice = unit.price,
        )
    }.sortedBy { it.productCode }
}
