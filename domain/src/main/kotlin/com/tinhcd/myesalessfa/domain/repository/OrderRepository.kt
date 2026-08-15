package com.tinhcd.myesalessfa.domain.repository

import com.tinhcd.myesalessfa.domain.DataResult
import com.tinhcd.myesalessfa.domain.model.DraftOrder

interface OrderRepository {
    /**
     * Sends [order]. The server prices it and marks the `take_order` step done in
     * the same transaction, so a stored order can never leave the rep still owing
     * the step.
     */
    suspend fun submit(order: DraftOrder): DataResult<Unit>
}
