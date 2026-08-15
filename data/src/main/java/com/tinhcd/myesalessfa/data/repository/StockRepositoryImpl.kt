package com.tinhcd.myesalessfa.data.repository

import com.tinhcd.myesalessfa.data.remote.StockApi
import com.tinhcd.myesalessfa.data.remote.StockCountLinePayload
import com.tinhcd.myesalessfa.data.remote.StockCountPayload
import com.tinhcd.myesalessfa.domain.DataResult
import com.tinhcd.myesalessfa.domain.model.DraftStockCount
import com.tinhcd.myesalessfa.domain.repository.StockRepository
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StockRepositoryImpl @Inject constructor(
    private val stockApi: StockApi,
) : StockRepository {

    override suspend fun previousCount(
        customerId: String,
        visitId: String,
    ): DataResult<Map<String, Int>> = try {
        // Already totalled per product in base units by the function; the same
        // product may have been counted loose and by the case, and only the
        // base-unit total compares.
        DataResult.Success(stockApi.previousCount(customerId, visitId))
    } catch (e: Exception) {
        DataResult.Failure(e.toAppError())
    }

    /**
     * What the server has for this visit. A count only exists once it has been
     * submitted, so this is the whole picture — the order screen measures its
     * suggestions against it.
     */
    override suspend fun countedBaseQty(visitId: String): DataResult<Map<String, Int>> = try {
        DataResult.Success(stockApi.visitCount(visitId))
    } catch (e: Exception) {
        DataResult.Failure(e.toAppError())
    }

    override suspend fun submit(count: DraftStockCount): DataResult<Unit> = try {
        stockApi.submit(
            StockCountPayload(
                // The idempotency key `submit_stock_count` conflicts on, so a retry
                // after a timeout that in fact succeeded does not wipe and rewrite
                // the count.
                id = UUID.randomUUID().toString(),
                visitId = count.visitId,
                countDate = LocalDate.now().toString(),
                note = count.note.trim().ifBlank { null },
                clientCreatedAt = OffsetDateTime.now(ZoneOffset.UTC).toString(),
                // Quantities only. The server derives base quantities from the
                // catalogue and looks up the previous figures itself.
                lines = count.lines.map {
                    StockCountLinePayload(
                        productId = it.productId,
                        uomCode = it.uomCode,
                        qty = it.qty,
                    )
                },
            ),
        )
        DataResult.Success(Unit)
    } catch (e: Exception) {
        DataResult.Failure(e.toAppError())
    }
}
