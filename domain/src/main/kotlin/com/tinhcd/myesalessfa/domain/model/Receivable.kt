package com.tinhcd.myesalessfa.domain.model

import java.time.LocalDate

/** One outlet's debt, as the list shows it. */
data class ReceivableCustomer(
    val customerId: String,
    val customerCode: String,
    val customerName: String,
    val phone: String?,
    val address: String?,
    val invoices: Int,
    val outstanding: Long,
    /** True when any single invoice is past its due date. */
    val overdue: Boolean,
)

/**
 * One open invoice.
 *
 * [outstanding] arrives from the server rather than being subtracted here. It is
 * billed minus every payment against it, including a colleague's, and the device
 * has no way to know about those.
 */
data class ReceivableInvoice(
    val invoiceId: String,
    val invoiceNo: String,
    val issuedOn: LocalDate,
    val dueOn: LocalDate,
    val total: Long,
    val paid: Long,
    val outstanding: Long,
    val note: String?,
) {
    fun isOverdue(today: LocalDate): Boolean = dueOn.isBefore(today)

    fun daysLate(today: LocalDate): Long =
        if (isOverdue(today)) today.toEpochDay() - dueOn.toEpochDay() else 0
}

/** What the rep is putting against one invoice, in dong. */
data class PaymentAllocation(
    val invoiceId: String,
    val amount: Long,
)

/**
 * A collection being entered: an amount against each of several invoices.
 *
 * The rep is handed one sum at the counter and splits it across whatever is
 * open, so the draft is the whole batch rather than one invoice at a time. It is
 * validated as a batch too — an allocation that overruns its invoice is caught
 * before anything is sent, because the alternative is the server accepting two
 * of three rows and the rep having to work out which.
 */
data class PaymentDraft(
    val customerId: String,
    /**
     * Minted once when the rep starts entering, and never again.
     *
     * This is what makes a retry safe rather than a second collection. The row
     * ids sent to the server are derived from it, so a save that times out and is
     * tapped again carries the same ids and collides on the primary key instead
     * of recording the money twice. Minting them at send time — the obvious
     * place — would defeat the whole mechanism, because every send would look
     * like a new batch.
     */
    val batchId: String,
    val amounts: Map<String, Long> = emptyMap(),
    val note: String = "",
) {
    val total: Long get() = amounts.values.sum()

    val allocations: List<PaymentAllocation>
        get() = amounts
            .filterValues { it > 0 }
            .map { (invoiceId, amount) -> PaymentAllocation(invoiceId, amount) }

    /**
     * The invoices this draft would overrun, by id.
     *
     * Checked against the balance the server sent rather than against the total
     * billed: paying the last 50,000 of a 2,000,000 invoice is not an overpayment
     * and refusing it would strand the debt.
     */
    fun overrun(invoices: List<ReceivableInvoice>): Set<String> {
        val balances = invoices.associate { it.invoiceId to it.outstanding }
        return amounts
            .filter { (id, amount) -> amount > (balances[id] ?: 0L) }
            .keys
    }

    fun canSubmit(invoices: List<ReceivableInvoice>): Boolean =
        allocations.isNotEmpty() && overrun(invoices).isEmpty()
}
