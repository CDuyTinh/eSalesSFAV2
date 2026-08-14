package com.tinhcd.myesalessfa.data.repository

import android.content.Context
import com.tinhcd.myesalessfa.data.local.CatalogDao
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
    private val catalogDao: CatalogDao,
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

    /**
     * The server's view merged with a count still in the outbox, queued winning.
     *
     * The queued one is by construction the newer of the two — it has not been
     * delivered — and it is the whole reason this merge exists: a rep who counted in
     * a dead spot must still get order suggestions rather than being told the shelf
     * is at par because the server has never heard otherwise.
     *
     * Base quantities are recomputed from the cached catalogue, because the outbox
     * payload carries only what the rep entered: product, unit and quantity.
     */
    override suspend fun countedBaseQty(visitId: String): DataResult<Map<String, Int>> = try {
        val remote = runCatching { stockApi.visitCount(visitId) }.getOrDefault(emptyMap())
        val queued = queuedCount(visitId)
        DataResult.Success(if (queued != null) queued else remote)
    } catch (e: Exception) {
        DataResult.Failure(e.toAppError())
    }

    /**
     * Null when nothing is queued for this visit — distinct from an empty map, which
     * would say the rep counted and found nothing at all.
     */
    private suspend fun queuedCount(visitId: String): Map<String, Int>? {
        val payloads = outboxDao.payloadsOfType(OutboxEntity.TYPE_STOCK_COUNT)
            .mapNotNull { raw ->
                runCatching { json.decodeFromString<StockCountPayload>(raw) }.getOrNull()
            }
            .filter { it.visitId == visitId }

        // A recount replaces rather than accumulates, so the newest queued entry is
        // the count — matching what submit_stock_count does server-side.
        val newest = payloads.maxByOrNull { it.clientCreatedAt } ?: return null

        val ratesByProductUom = catalogDao.saleUnits()
            .associate { (it.productId to it.uomCode) to it.conversionRate }

        val totals = mutableMapOf<String, Int>()
        for (line in newest.lines) {
            val rate = ratesByProductUom[line.productId to line.uomCode] ?: continue
            totals[line.productId] = (totals[line.productId] ?: 0) + line.qty * rate
        }
        return totals
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
