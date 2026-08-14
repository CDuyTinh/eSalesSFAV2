package com.tinhcd.myesalessfa.data.repository

import android.content.Context
import com.tinhcd.myesalessfa.data.local.OutboxDao
import com.tinhcd.myesalessfa.data.local.OutboxEntity
import com.tinhcd.myesalessfa.data.outbox.OrderLinePayload
import com.tinhcd.myesalessfa.data.outbox.OrderPayload
import com.tinhcd.myesalessfa.data.outbox.OutboxFlusher
import com.tinhcd.myesalessfa.data.outbox.OutboxWorker
import com.tinhcd.myesalessfa.domain.DataResult
import com.tinhcd.myesalessfa.domain.model.DraftOrder
import com.tinhcd.myesalessfa.domain.repository.OrderRepository
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
 * Orders go through the outbox, like check-ins and for the same reason: the rep
 * has already agreed the order with the customer by the time they press submit,
 * and no retry can reconstruct that conversation. Unlike a check-in, an order
 * also cannot be re-entered from memory.
 */
@Singleton
class OrderRepositoryImpl @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val outboxDao: OutboxDao,
    private val flusher: OutboxFlusher,
) : OrderRepository {

    override suspend fun submit(order: DraftOrder): DataResult<SubmitOutcome> = try {
        // Minted here, before anything is sent. It is the idempotency key
        // `submit_order` conflicts on, so a replay after a timeout that actually
        // succeeded books nothing twice.
        val orderId = UUID.randomUUID().toString()

        val payload = OrderPayload(
            id = orderId,
            visitId = order.visitId,
            // The day the rep agreed it, which is what the server prices against.
            orderDate = LocalDate.now().toString(),
            note = order.note.trim().ifBlank { null },
            clientTotalAmount = order.totalAmount,
            clientCreatedAt = OffsetDateTime.now(ZoneOffset.UTC).toString(),
            lines = order.lines.mapIndexed { index, line ->
                OrderLinePayload(
                    lineNo = index + 1,
                    productId = line.productId,
                    uomCode = line.uomCode,
                    qty = line.qty,
                )
            },
        )

        outboxDao.insert(
            OutboxEntity(
                type = OutboxEntity.TYPE_ORDER,
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
