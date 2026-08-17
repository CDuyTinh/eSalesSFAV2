package com.tinhcd.myesalessfa.domain.repository

import com.tinhcd.myesalessfa.domain.DataResult
import com.tinhcd.myesalessfa.domain.model.PaymentDraft
import com.tinhcd.myesalessfa.domain.model.ReceivableCustomer
import com.tinhcd.myesalessfa.domain.model.ReceivableInvoice

/** What outlets owe, and the money collected against it. */
interface ReceivableRepository {

    suspend fun customers(): DataResult<List<ReceivableCustomer>>

    suspend fun invoices(customerId: String): DataResult<List<ReceivableInvoice>>

    /**
     * Records the whole batch or none of it.
     *
     * [visitId] ties the collection to the call it happened during, when it
     * happened during one — a rep can also be handed money outside a visit.
     */
    suspend fun collect(draft: PaymentDraft, visitId: String?): DataResult<Unit>
}
