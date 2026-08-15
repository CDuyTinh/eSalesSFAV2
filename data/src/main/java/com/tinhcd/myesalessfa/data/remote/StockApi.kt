package com.tinhcd.myesalessfa.data.remote

import com.tinhcd.myesalessfa.data.remote.http.orThrow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stock counting.
 *
 * `/submit-stock-count` forwards to the `submit_stock_count` database function,
 * which writes the header, the lines and the `stock_outlet` step in one
 * transaction, fills each line's previous figure from the customer's last count,
 * and is idempotent on the client-minted id so an outbox replay is a no-op.
 */
@Singleton
class StockApi @Inject constructor(
    private val service: FunctionsService,
) {
    suspend fun submit(payload: StockCountPayload) {
        service.submitStockCount(payload).orThrow()
    }

    /**
     * The customer's last count, totalled per product in base units, so the rep
     * sees "last time" beside each product while counting.
     *
     * The summing is done server-side: one product may have been counted loose and
     * by the case in the same visit, and only the base-unit total compares.
     */
    suspend fun previousCount(customerId: String, exceptVisitId: String): Map<String, Int> =
        service.previousCount(customerId = customerId, exceptVisitId = exceptVisitId).previous

    /** This visit's own count, which the order screen measures against par. */
    suspend fun visitCount(visitId: String): Map<String, Int> =
        service.visitCount(visitId).counted
}
