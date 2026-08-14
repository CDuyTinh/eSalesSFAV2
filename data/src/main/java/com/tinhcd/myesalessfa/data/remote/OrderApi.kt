package com.tinhcd.myesalessfa.data.remote

import com.tinhcd.myesalessfa.data.outbox.OrderPayload
import com.tinhcd.myesalessfa.data.remote.http.orThrow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Order submission, which is the one write in this app that is not a plain table
 * insert.
 *
 * `submit_order` prices the order, writes the header and lines, and records the
 * `take_order` step, all in one transaction. Doing that from the client would
 * mean three round trips that can each fail separately, leaving an order with no
 * lines or a step marked done for an order that never landed. It is also
 * idempotent on the order id, which is what makes an outbox replay safe.
 */
@Singleton
class OrderApi @Inject constructor(
    private val service: PostgrestService,
    private val json: Json,
) {
    suspend fun submit(payload: OrderPayload) {
        service.submitOrder(
            buildJsonObject { put("p_order", json.encodeToJsonElement(payload)) },
        ).orThrow()
    }
}
