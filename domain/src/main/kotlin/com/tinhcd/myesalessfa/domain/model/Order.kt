package com.tinhcd.myesalessfa.domain.model

import java.time.LocalDate

/**
 * Ordering, priced entirely in integers.
 *
 * Amounts are dong, which has no subunit anyone transacts in, so every figure
 * here is a `Long` and VAT is a rate in basis points (1000 = 10%) rather than a
 * fraction. `submit_order` on the server runs the identical arithmetic, and the
 * two must agree to the dong: the rep reads a total out to the customer before
 * the order has been anywhere near the server, and an order the ERP later
 * disputes by 1 VND is an order somebody has to go back and explain.
 */

/** A unit a product may be sold in — legacy product_uoms. */
data class SaleUnit(
    val uomCode: String,
    val uomName: String,
    /**
     * Base units contained in one of this unit: 1 CASE = 24 PCS gives 24. The
     * base unit has its own row, at 1, so nothing downstream special-cases it.
     */
    val conversionRate: Int,
    val isDefault: Boolean,
    val sortOrder: Int = 0,
)

data class Product(
    val id: String,
    val code: String,
    val name: String,
    val categoryName: String?,
    val baseUomCode: String,
    val vatBasisPoints: Int,
)

/** One effective-dated row of the price list. */
data class PriceRule(
    val productId: String,
    val uomCode: String,
    /** Null is the list price, used by any customer with no class-specific row. */
    val classId: String?,
    val price: Long,
    val fromDate: LocalDate,
    val toDate: LocalDate,
)

data class PricedUnit(
    val unit: SaleUnit,
    val price: Long,
)

/** A product with the units this customer may order it in, already priced. */
data class PricedProduct(
    val product: Product,
    val units: List<PricedUnit>,
) {
    init {
        // A product with nothing to sell it by is not orderable, and every
        // caller below assumes it can offer the rep at least one unit.
        require(units.isNotEmpty()) { "PricedProduct ${product.code} has no priced unit" }
    }

    val defaultUnit: PricedUnit
        get() = units.firstOrNull { it.unit.isDefault } ?: units.first()
}

/**
 * The price one customer pays for one unit of one product on one day, or null
 * when the product is not sellable to them at all.
 *
 * A class-specific row beats the list price. Where a class row and a list row
 * both cover the date, taking the wrong one silently overcharges or
 * undercharges a whole customer class, so the preference is explicit rather
 * than left to whatever order the rows arrived in.
 */
fun List<PriceRule>.priceFor(
    productId: String,
    uomCode: String,
    classId: String?,
    on: LocalDate,
): Long? = this
    .filter {
        it.productId == productId &&
            it.uomCode == uomCode &&
            (it.classId == null || it.classId == classId) &&
            !on.isBefore(it.fromDate) &&
            !on.isAfter(it.toDate)
    }
    // A row carrying a class is more specific than one that does not.
    .minByOrNull { if (it.classId != null) 0 else 1 }
    ?.price

/**
 * Prices a catalogue for one customer.
 *
 * Units with no price on [on] are dropped, and a product left with no unit
 * disappears entirely: the server refuses to book an unpriced line, so offering
 * one to the rep only sets up a rejection after they have already told the
 * customer it was ordered.
 */
fun priceCatalogue(
    products: List<Product>,
    unitsByProduct: Map<String, List<SaleUnit>>,
    priceRules: List<PriceRule>,
    classId: String?,
    on: LocalDate,
): List<PricedProduct> = products.mapNotNull { product ->
    val priced = unitsByProduct[product.id]
        .orEmpty()
        .sortedBy { it.sortOrder }
        .mapNotNull { unit ->
            priceRules.priceFor(product.id, unit.uomCode, classId, on)
                ?.let { PricedUnit(unit, it) }
        }
    if (priced.isEmpty()) null else PricedProduct(product, priced)
}

/**
 * VAT on [gross] at [basisPoints], rounded half-up.
 *
 * `(gross * bp + 5000) / 10000` is exactly what the server computes. Anything
 * that introduces a Double, or rounds half-to-even, drifts from it. Assumes a
 * non-negative gross, which qty > 0 and price >= 0 guarantee.
 */
internal fun vatOf(gross: Long, basisPoints: Int): Long =
    (gross * basisPoints + 5000) / 10000

/**
 * A line of a draft order, carrying its own snapshot of everything that prices
 * it. The catalogue can be re-priced or re-packed tomorrow; what the customer
 * agreed to today must not move with it, which is also why the server stores
 * these same values per line rather than re-deriving them on read.
 */
data class OrderLine(
    val productId: String,
    val productCode: String,
    val productName: String,
    val uomCode: String,
    val uomName: String,
    val conversionRate: Int,
    val qty: Int,
    val unitPrice: Long,
    val vatBasisPoints: Int,
) {
    /** Quantity in base units, which is what stock and reporting work in. */
    val baseQty: Int get() = qty * conversionRate

    val grossAmount: Long get() = qty.toLong() * unitPrice

    val vatAmount: Long get() = vatOf(grossAmount, vatBasisPoints)

    val lineAmount: Long get() = grossAmount + vatAmount
}

data class DraftOrder(
    val visitId: String,
    val customerId: String,
    val lines: List<OrderLine> = emptyList(),
    val note: String = "",
) {
    val subTotal: Long get() = lines.sumOf { it.grossAmount }

    /**
     * VAT is summed per line, never taken on the subtotal: the catalogue mixes
     * 8% and 10% products, so one rate applied to the whole order would be wrong
     * for at least one line of most orders.
     */
    val vatAmount: Long get() = lines.sumOf { it.vatAmount }

    val totalAmount: Long get() = subTotal + vatAmount

    /**
     * Units ordered, added across lines.
     *
     * Deliberately not converted to base units: this is the legacy checkout's
     * "tổng số lượng", which a rep reads back as "twelve things", and turning two
     * cases and three bottles into 51 pieces answers a question nobody asked.
     */
    val totalQty: Int get() = lines.sumOf { it.qty }

    val canSubmit: Boolean get() = lines.isNotEmpty()

    /**
     * Base units of one product across every unit it was ordered in.
     *
     * A product can hold two lines — a case and a few loose pieces — so a caller
     * asking "how much of this is in the basket" cannot read one line.
     */
    fun baseQtyOf(productId: String): Int = lines
        .filter { it.productId == productId }
        .sumOf { it.baseQty }

    fun lineFor(productId: String, uomCode: String): OrderLine? =
        lines.firstOrNull { it.productId == productId && it.uomCode == uomCode }

    /**
     * Adds [line], replacing any existing line for the same product and unit.
     * The server holds a unique constraint on (order, product, unit), so
     * appending a second line for the same thing would be rejected — and would
     * make the total depend on how many times the rep happened to tap.
     */
    fun withLine(line: OrderLine): DraftOrder {
        val without = lines.filterNot {
            it.productId == line.productId && it.uomCode == line.uomCode
        }
        return copy(lines = without + line)
    }

    fun withoutLine(productId: String, uomCode: String): DraftOrder = copy(
        lines = lines.filterNot { it.productId == productId && it.uomCode == uomCode },
    )

    fun quantityOf(productId: String, uomCode: String): Int =
        lines.firstOrNull { it.productId == productId && it.uomCode == uomCode }?.qty ?: 0
}
