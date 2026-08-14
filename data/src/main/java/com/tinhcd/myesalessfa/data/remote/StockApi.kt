package com.tinhcd.myesalessfa.data.remote

import com.tinhcd.myesalessfa.data.outbox.StockCountPayload
import com.tinhcd.myesalessfa.data.remote.http.orThrow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stock counting.
 *
 * `submit_stock_count` writes the header, the lines and the `stock_outlet` step
 * in one transaction, fills each line's previous figure from the customer's last
 * count, and is idempotent on the client-minted id so an outbox replay is a
 * no-op.
 */
@Singleton
class StockApi @Inject constructor(
    private val service: PostgrestService,
    private val json: Json,
) {
    suspend fun submit(payload: StockCountPayload) {
        service.submitStockCount(
            buildJsonObject { put("p_count", json.encodeToJsonElement(payload)) },
        ).orThrow()
    }

    /**
     * The customer's most recent count, so the rep sees "last time" beside each
     * product while counting rather than only after submitting.
     *
     * Excludes the visit in progress: on a recount, the figure worth comparing
     * against is the previous visit's, not the attempt being replaced. This
     * mirrors what the RPC stores.
     */
    suspend fun previousCount(customerId: String, exceptVisitId: String): PreviousCountDto? =
        service.stockCounts(
            customerId = Filters.eq(customerId),
            exceptVisitId = Filters.neq(exceptVisitId),
        ).firstOrNull()
}
