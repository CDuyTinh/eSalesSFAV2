package com.tinhcd.myesalessfa.domain.repository

import com.tinhcd.myesalessfa.domain.DataResult
import com.tinhcd.myesalessfa.domain.model.CartLine
import com.tinhcd.myesalessfa.domain.model.DraftOrder

interface OrderRepository {
    /**
     * Sends [order]. The server prices it and marks the `take_order` step done in
     * the same transaction, so a stored order can never leave the rep still owing
     * the step. Booking it also empties this outlet's basket.
     */
    suspend fun submit(order: DraftOrder): DataResult<Unit>

    /**
     * The basket this rep has building for [customerId], as product and unit
     * against a quantity. Empty when there is none.
     *
     * Prices are not stored with it: the catalogue prices the screen and the
     * server prices the booking, both from the same effective-dated list, and a
     * third copy could only disagree with those two.
     */
    suspend fun cart(customerId: String): DataResult<List<CartLine>>

    /**
     * Makes the stored basket match [lines]. The whole basket is sent, not a
     * change to it: what the rep is looking at is the truth, and a merge would
     * need a removal to travel as a quantity of zero — a rule that is silent when
     * it is got wrong.
     */
    suspend fun saveCart(customerId: String, lines: List<CartLine>): DataResult<Unit>
}
