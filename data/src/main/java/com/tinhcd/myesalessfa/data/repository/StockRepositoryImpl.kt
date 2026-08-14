package com.tinhcd.myesalessfa.data.repository

import android.content.Context
import com.tinhcd.myesalessfa.data.local.OutboxDao
import com.tinhcd.myesalessfa.data.local.OutboxEntity
import com.tinhcd.myesalessfa.data.outbox.OutboxFlusher
import com.tinhcd.myesalessfa.data.outbox.OutboxWorker
import com.tinhcd.myesalessfa.data.outbox.StockCountLinePayload
import com.tinhcd.myesalessfa.data.outbox.StockCountPayload
import com.tinhcd.myesalessfa.data.remote.StockApi
import com.tinhcd.myesalessfa.domain.DataResult
import com.tinhcd.myesalessfa.domain.model.DraftStockCount
import com.tinhcd.myesalessfa.domain.repository.StockRepository
import com.tinhcd.myesalessfa.domain.repository.SubmitOutcome
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private val json = Json { encodeDefaults = true; explicitNulls = false }

/**
 * Stock counts go through the outbox. A count is the least reconstructable thing
 * the app records: the rep walked the shelves and looked, and once they are out
 * of the shop neither they nor anyone else can redo it from memory.
 */
@Singleton
class StockRepositoryImpl @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val stockApi: StockApi,
    private val outboxDao: OutboxDao,
    private val flusher: OutboxFlusher,
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

    override suspend fun submit(count: DraftStockCount): DataResult<SubmitOutcome> = try {
        // Minted before anything is sent: the idempotency key submit_stock_count
        // conflicts on, so a replay after a timeout that in fact succeeded does
        // not wipe and rewrite the count.
        val countId = UUID.randomUUID().toString()

        val payload = StockCountPayload(
            id = countId,
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
        )

        outboxDao.insert(
            OutboxEntity(
                type = OutboxEntity.TYPE_STOCK_COUNT,
                payload = json.encodeToString(payload),
                createdAt = System.currentTimeMillis(),
            ),
        )

        val drained = runCatching { flusher.flush() }.getOrDefault(false)
        if (drained) {
            DataResult.Success(SubmitOutcome.SENT)
        } else {
            OutboxWorker.enqueue(context)
            DataResult.Success(SubmitOutcome.QUEUED)
        }
    } catch (e: Exception) {
        DataResult.Failure(e.toAppError())
    }
}
