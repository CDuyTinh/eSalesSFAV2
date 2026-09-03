package com.tinhcd.myesalessfa.domain.model

import com.tinhcd.myesalessfa.domain.foldForSearch

/**
 * How the product list is ordered, as the app this replaces offers it.
 *
 * The legacy sheet has three: price, promotion, and "already in the basket".
 * Promotions are not calculated in this build, so that one is absent rather than
 * present and inert — a sort that never changes the order teaches a rep to stop
 * trusting the sheet.
 */
enum class ProductSort(val label: String) {
    /** The catalogue's own order, which head office set. */
    DEFAULT("Mặc định"),
    PRICE_ASC("Giá thấp đến cao"),
    IN_BASKET("Đã thêm vào giỏ"),
}

/**
 * The product list as the rep has narrowed it: searched, filtered by category,
 * then sorted.
 *
 * Pure, and here rather than in the ViewModel, because this is the only layer
 * this codebase can unit test — and because getting it wrong is quiet. A search
 * that misses a product the rep knows is stocked reads as the catalogue being
 * out of date.
 *
 * @param qtyOf base-unit quantity of that product already in the basket, used
 *  only by [ProductSort.IN_BASKET].
 */
fun List<PricedProduct>.browse(
    query: String = "",
    categories: Set<String> = emptySet(),
    sort: ProductSort = ProductSort.DEFAULT,
    qtyOf: (String) -> Int = { 0 },
): List<PricedProduct> {
    val needle = query.trim().foldForSearch()

    val matched = filter { priced ->
        val product = priced.product
        // An empty selection means "no category filter", not "no categories".
        val inCategory = categories.isEmpty() || product.categoryName in categories
        val matches = needle.isEmpty() ||
            product.name.foldForSearch().contains(needle) ||
            product.code.foldForSearch().contains(needle)
        inCategory && matches
    }

    return when (sort) {
        ProductSort.DEFAULT -> matched
        // The default unit's price, because that is the figure printed on the row.
        // Sorting by a unit the rep cannot see would look arbitrary.
        ProductSort.PRICE_ASC -> matched.sortedBy { it.defaultUnit.price }
        // Descending, so what is already in the basket floats to the top — the
        // legacy sheet's "CARTADDED". Ties keep catalogue order.
        ProductSort.IN_BASKET -> matched.sortedByDescending { qtyOf(it.product.id) }
    }
}

/** The category names present in a catalogue, for the filter sheet. */
fun List<PricedProduct>.categoryNames(): List<String> =
    mapNotNull { it.product.categoryName }.distinct().sorted()
