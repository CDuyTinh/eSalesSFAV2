package com.tinhcd.myesalessfa.domain.model

import java.time.LocalDate

/**
 * One past order at this outlet, as the history tab lists it.
 *
 * Not [DraftOrder] or anything the take-order screen builds. That one is being
 * assembled and carries prices, units and VAT so it can be totalled; this one is
 * finished, and the only figures that matter are what it came to and what was in
 * it.
 *
 * Scoped to the rep reading it, because `sales_order` is: this is the history
 * they wrote, not the outlet's whole history. Showing another rep's orders would
 * put a number in front of the shop that the person standing there cannot
 * explain.
 */
data class CustomerOrder(
    val orderId: String,
    val orderNo: String,
    val orderDate: LocalDate,
    val status: String,
    val totalAmount: Long,
    val lines: List<CustomerOrderLine>,
) {
    /**
     * Distinct products, not lines.
     *
     * An order can carry the same product twice — a case and a few loose pieces
     * is a normal shape, and the order keys a line by product *and* unit. On the
     * shelf that is one SKU, which is what a rep means saying "hai mặt hàng",
     * and it is the same rule `dashboard_overview` counts SKU/đơn by.
     */
    val skuCount: Int get() = lines.distinctBy { it.productCode }.size

    /**
     * Summed across units, so a case and two pieces reads as three. Deliberately
     * not base units: the rep counts what they handed over, not what it converts
     * to.
     */
    val totalQty: Int get() = lines.sumOf { it.qty }
}

data class CustomerOrderLine(
    val productCode: String,
    val productName: String,
    val uomCode: String,
    val qty: Int,
    val lineAmount: Long,
)
