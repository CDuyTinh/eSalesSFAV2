package com.tinhcd.myesalessfa.data.repository

import com.tinhcd.myesalessfa.data.remote.dto.CartItemDto
import com.tinhcd.myesalessfa.data.remote.dto.CartPayload
import com.tinhcd.myesalessfa.data.remote.dto.OrderLinePayload
import com.tinhcd.myesalessfa.data.remote.dto.OrderPayload
import com.tinhcd.myesalessfa.data.remote.http.orThrow
import com.tinhcd.myesalessfa.data.remote.service.OrderService
import com.tinhcd.myesalessfa.domain.DataResult
import com.tinhcd.myesalessfa.domain.model.CartLine
import com.tinhcd.myesalessfa.domain.model.DraftOrder
import com.tinhcd.myesalessfa.domain.repository.OrderRepository
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * `/submit-order` forwards to the `submit_order` database function, which prices the
 * order, writes the header and lines, and records the `take_order` step in one
 * transaction. Doing that from the client would mean three round trips that can each
 * fail separately, leaving an order with no lines or a step marked done for an order
 * that never landed.
 */
@Singleton
class OrderRepositoryImpl @Inject constructor(
    private val service: OrderService,
) : OrderRepository {

    override suspend fun submit(order: DraftOrder): DataResult<Unit> = try {
        service.submitOrder(
            OrderPayload(
                // The draft's own id, minted once when the basket was opened. It
                // is the key `submit_order` conflicts on, so a retry after a
                // timeout that in fact succeeded books nothing twice — which a
                // fresh UUID per call, as this used to do, could not deliver.
                id = order.id,
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
            ),
        ).orThrow()
        DataResult.Success(Unit)
    } catch (e: Exception) {
        DataResult.Failure(e.toAppError())
    }

    override suspend fun cart(customerId: String): DataResult<List<CartLine>> = try {
        DataResult.Success(
            service.cart(customerId).items.map { CartLine(it.productId, it.uomCode, it.qty) },
        )
    } catch (e: Exception) {
        DataResult.Failure(e.toAppError())
    }

    override suspend fun saveCart(
        customerId: String,
        lines: List<CartLine>,
    ): DataResult<Unit> = try {
        service.saveCart(
            CartPayload(
                customerId = customerId,
                items = lines.map { CartItemDto(it.productId, it.uomCode, it.qty) },
            ),
        ).orThrow()
        DataResult.Success(Unit)
    } catch (e: Exception) {
        DataResult.Failure(e.toAppError())
    }
}
