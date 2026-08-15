package com.tinhcd.myesalessfa.domain.model

/**
 * Turning a shelf shortfall into something orderable.
 *
 * The stock count says how far below par each required SKU is, in base units. An
 * order is written in sale units — cases, packs, pieces. Bridging the two is the
 * last step of the chain that started with must-stock lists, and it is the reason
 * a par level of 48 pieces can be acted on rather than merely reported.
 */

/**
 * One orderable piece of a suggestion: so many of one sale unit.
 *
 * A suggestion is a list of these rather than a single quantity because a shortfall
 * rarely lands on a case boundary. Twenty-six pieces short of par is one case and
 * two loose pieces, not two cases — and the order can say so, since a line is keyed
 * by product *and* unit on both the client and the server.
 */
data class SuggestedPart(
    val uomCode: String,
    val uomName: String,
    val conversionRate: Int,
    /** Whole [uomCode] units to order. Advisory: the rep edits it. */
    val qty: Int,
    val unitPrice: Long,
) {
    val baseQty: Int get() = qty * conversionRate
}

data class OrderSuggestion(
    val productId: String,
    val productCode: String,
    val productName: String,
    /** What the outlet is obliged to hold, in base units. */
    val parBaseQty: Int,
    /** What this visit's count found, in base units. */
    val countedBaseQty: Int,
    /** Largest unit first, so "1 case + 2 pieces" reads in that order. */
    val parts: List<SuggestedPart>,
) {
    val shortfallBaseQty: Int get() = (parBaseQty - countedBaseQty).coerceAtLeast(0)

    val suggestedBaseQty: Int get() = parts.sumOf { it.baseQty }

    /**
     * The unit the rep's picker should land on when the suggestion is applied — the
     * biggest part, since that is the bulk of the order. The smaller parts are still
     * real lines; the product row lists every line it has, so none of them is
     * invisible just because the picker is elsewhere.
     */
    val primaryPart: SuggestedPart? get() = parts.firstOrNull()

    /**
     * Base units the suggestion overshoots par by, because sale units do not always
     * divide the shortfall evenly. Worth showing: a rep asked to order a whole case
     * to cover a two-piece gap should be able to see that is what is happening.
     *
     * Splitting into smaller units drives this to zero whenever the product is sold
     * loose, so what is left is a genuine constraint of the pack sizes on offer
     * rather than arithmetic the app could not be bothered to do.
     */
    val overshootBaseQty: Int
        get() = (suggestedBaseQty - shortfallBaseQty).coerceAtLeast(0)
}

/**
 * Splits [shortfallBaseQty] across the sale units the customer may actually buy,
 * biggest first.
 *
 * Every unit but the smallest takes whole ones only; the smallest rounds up. That
 * combination is what makes the total the least that still reaches par: rounding up
 * anywhere else would buy a surplus the next unit down could have covered exactly,
 * and rounding down on the smallest would leave the shelf below the par level this
 * whole exercise exists to restore.
 *
 * So a 26-piece gap on a product sold by the 24-case and singly comes back as one
 * case and two pieces. The same gap on a product sold only by the case is still one
 * case with 22 to spare — the pack sizes leave nothing better, which is exactly what
 * [OrderSuggestion.overshootBaseQty] then reports.
 *
 * Units are deduplicated by conversion rate: two units of the same size are
 * contradictory catalogue data, and offering both would produce two lines for one
 * intention. A non-positive rate would be corrupt data too and is dropped rather
 * than divided by mid-visit.
 */
fun splitShortfall(shortfallBaseQty: Int, units: List<PricedUnit>): List<SuggestedPart> {
    if (shortfallBaseQty <= 0) return emptyList()

    val usable = units
        .filter { it.unit.conversionRate > 0 }
        .distinctBy { it.unit.conversionRate }
        .sortedByDescending { it.unit.conversionRate }
    if (usable.isEmpty()) return emptyList()

    val parts = mutableListOf<SuggestedPart>()
    var remaining = shortfallBaseQty

    usable.forEachIndexed { index, priced ->
        if (remaining <= 0) return@forEachIndexed
        val rate = priced.unit.conversionRate

        val qty = if (index == usable.lastIndex) {
            // The last chance to reach par, so this one rounds up.
            (remaining + rate - 1) / rate
        } else {
            remaining / rate
        }
        if (qty <= 0) return@forEachIndexed

        parts += SuggestedPart(
            uomCode = priced.unit.uomCode,
            uomName = priced.unit.uomName,
            conversionRate = rate,
            qty = qty,
            unitPrice = priced.price,
        )
        remaining -= qty * rate
    }

    return parts
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
        val parts = splitShortfall(shortfall, priced.units)
        if (parts.isEmpty()) return@mapNotNull null

        OrderSuggestion(
            productId = productId,
            productCode = priced.product.code,
            productName = priced.product.name,
            parBaseQty = parBaseQty,
            countedBaseQty = counted,
            parts = parts,
        )
    }.sortedBy { it.productCode }
}
