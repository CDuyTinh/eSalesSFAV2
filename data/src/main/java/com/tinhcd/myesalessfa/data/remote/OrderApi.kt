package com.tinhcd.myesalessfa.data.remote

import com.tinhcd.myesalessfa.data.remote.http.orThrow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Order submission.
 *
 * `/submit-order` forwards to the `submit_order` database function, which prices
 * the order, writes the header and lines, and records the `take_order` step in one
 * transaction. Doing that from the client would mean three round trips that can
 * each fail separately, leaving an order with no lines or a step marked done for
 * an order that never landed. It is idempotent on the order id, so a rep who taps
 * submit again after a timeout does not book the order twice.
 *
 * The payload's field names are the database function's own, so it travels to
 * plpgsql without a second mapping that could disagree.
 */
@Singleton
class OrderApi @Inject constructor(
    private val service: FunctionsService,
) {
    suspend fun submit(payload: OrderPayload) {
        service.submitOrder(payload).orThrow()
    }
}
