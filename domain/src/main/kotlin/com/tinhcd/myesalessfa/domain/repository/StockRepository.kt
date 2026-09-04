package com.tinhcd.myesalessfa.domain.repository

import com.tinhcd.myesalessfa.domain.DataResult
import com.tinhcd.myesalessfa.domain.model.DraftStockCount

interface StockRepository {
    /**
     * This product's base-unit total at the customer's previous count, keyed by
     * product id. Empty when the outlet has never been counted.
     *
     * Excludes [visitId] so a recount compares against the previous visit rather
     * than against the attempt it is replacing.
     */
    suspend fun previousCount(customerId: String, visitId: String): DataResult<Map<String, Int>>

    /**
     * What this visit's count found, as product id -> base units. Empty when
     * nothing has been counted on this visit.
     */
    suspend fun countedBaseQty(visitId: String): DataResult<Map<String, Int>>

    /**
     * Products this outlet has bought over the last [months] months — what the
     * count sheet should open on, rather than the whole catalogue.
     *
     * Empty is a real answer and not an error: the orders behind it are scoped to
     * the reading rep, so an outlet a colleague covered last month contributes
     * nothing. Callers fall back to the full catalogue rather than showing an
     * empty sheet.
     */
    suspend fun purchasedProducts(
        customerId: String,
        months: Int = 3,
    ): DataResult<Set<String>>

    /**
     * Sends [count]. The server fills each line's previous figure and marks the
     * `stock_outlet` step done in the same transaction.
     */
    suspend fun submit(count: DraftStockCount): DataResult<Unit>
}
