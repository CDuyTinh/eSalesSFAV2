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
     * Sends [count]. The server fills each line's previous figure and marks the
     * `stock_outlet` step done in the same transaction.
     */
    suspend fun submit(count: DraftStockCount): DataResult<Unit>
}
