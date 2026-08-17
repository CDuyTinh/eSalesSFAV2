package com.tinhcd.myesalessfa.data.repository

import com.tinhcd.myesalessfa.data.remote.dto.CollectPaymentDto
import com.tinhcd.myesalessfa.data.remote.dto.PaymentAllocationDto
import com.tinhcd.myesalessfa.data.remote.dto.ReceivableCustomerDto
import com.tinhcd.myesalessfa.data.remote.dto.ReceivableInvoiceDto
import com.tinhcd.myesalessfa.data.remote.http.orThrow
import com.tinhcd.myesalessfa.data.remote.service.ReceivableService
import com.tinhcd.myesalessfa.domain.DataResult
import com.tinhcd.myesalessfa.domain.model.PaymentDraft
import com.tinhcd.myesalessfa.domain.model.ReceivableCustomer
import com.tinhcd.myesalessfa.domain.model.ReceivableInvoice
import com.tinhcd.myesalessfa.domain.repository.ReceivableRepository
import java.time.LocalDate
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ReceivableRepositoryImpl @Inject constructor(
    private val service: ReceivableService,
) : ReceivableRepository {

    override suspend fun customers(): DataResult<List<ReceivableCustomer>> = try {
        DataResult.Success(service.customers().orThrow().customers.map { it.toDomain() })
    } catch (e: Exception) {
        DataResult.Failure(e.toAppError())
    }

    override suspend fun invoices(customerId: String): DataResult<List<ReceivableInvoice>> = try {
        DataResult.Success(service.invoices(customerId).orThrow().invoices.map { it.toDomain() })
    } catch (e: Exception) {
        DataResult.Failure(e.toAppError())
    }

    /**
     * Row ids are derived from the draft's batch id, not minted here.
     *
     * A random id per call would make every retry a second collection: the rep
     * taps save, the connection stalls, they tap again, and the money is recorded
     * twice with nothing to tie the two rows together. Deriving from
     * (batch, invoice) means the same draft always produces the same ids, so the
     * second arrival collides on the primary key and the server reports it as the
     * replay it is.
     */
    override suspend fun collect(draft: PaymentDraft, visitId: String?): DataResult<Unit> = try {
        service.collect(
            CollectPaymentDto(
                visitId = visitId,
                collectedOn = LocalDate.now().toString(),
                note = draft.note.trim().ifBlank { null },
                allocations = draft.allocations.map {
                    PaymentAllocationDto(
                        id = allocationId(draft.batchId, it.invoiceId),
                        invoiceId = it.invoiceId,
                        amount = it.amount,
                    )
                },
            ),
        ).orThrow()
        DataResult.Success(Unit)
    } catch (e: Exception) {
        DataResult.Failure(e.toAppError())
    }
}

/**
 * One id per invoice per batch, stable for as long as the draft exists.
 *
 * The amount is deliberately not part of it, and the trade is worth stating.
 * Folding the amount in would make a corrected-then-resent batch a new row: if
 * the first attempt had in fact landed before the timeout, the outlet would be
 * credited twice. Leaving it out inverts the failure — a rep who edits a figure
 * after a save that silently succeeded gets their correction ignored as a replay.
 *
 * Losing a correction is recoverable and visible: the invoice's balance is
 * re-read straight after and shows what was actually recorded. Collecting the
 * same money twice is neither.
 */
private fun allocationId(batchId: String, invoiceId: String): String =
    UUID.nameUUIDFromBytes("$batchId:$invoiceId".toByteArray()).toString()

private fun ReceivableCustomerDto.toDomain() = ReceivableCustomer(
    customerId = customerId,
    customerCode = customerCode,
    customerName = customerName,
    phone = phone,
    address = address,
    invoices = invoices,
    outstanding = outstanding,
    overdue = overdue,
)

private fun ReceivableInvoiceDto.toDomain() = ReceivableInvoice(
    invoiceId = invoiceId,
    invoiceNo = invoiceNo,
    issuedOn = LocalDate.parse(issuedOn),
    dueOn = LocalDate.parse(dueOn),
    total = total,
    paid = paid,
    outstanding = outstanding,
    note = note,
)
