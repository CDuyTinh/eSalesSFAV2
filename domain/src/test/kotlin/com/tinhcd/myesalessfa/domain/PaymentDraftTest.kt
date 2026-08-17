package com.tinhcd.myesalessfa.domain

import com.tinhcd.myesalessfa.domain.model.PaymentDraft
import com.tinhcd.myesalessfa.domain.model.ReceivableInvoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class PaymentDraftTest {

    private fun invoice(
        id: String,
        total: Long,
        paid: Long = 0,
        dueOn: LocalDate = LocalDate.of(2026, 9, 1),
    ) = ReceivableInvoice(
        invoiceId = id,
        invoiceNo = "HD$id",
        issuedOn = LocalDate.of(2026, 8, 1),
        dueOn = dueOn,
        total = total,
        paid = paid,
        outstanding = total - paid,
        note = null,
    )

    private val invoices = listOf(
        invoice("a", total = 1_000_000),
        invoice("b", total = 5_000_000, paid = 4_500_000),
    )

    private fun draft(vararg amounts: Pair<String, Long>) =
        PaymentDraft(customerId = "c1", batchId = "batch", amounts = amounts.toMap())

    @Test
    fun `the total is what will be collected`() {
        assertEquals(1_400_000L, draft("a" to 900_000L, "b" to 500_000L).total)
    }

    @Test
    fun `zero amounts are not allocations`() {
        // A rep who types a figure and deletes it has not decided to collect zero
        // from that invoice; there is simply nothing to send.
        val d = draft("a" to 0L, "b" to 500_000L)

        assertEquals(1, d.allocations.size)
        assertEquals("b", d.allocations.single().invoiceId)
    }

    @Test
    fun `an empty draft cannot be submitted`() {
        assertFalse(draft().canSubmit(invoices))
        assertFalse(draft("a" to 0L).canSubmit(invoices))
    }

    @Test
    fun `paying the exact balance is allowed`() {
        // The common case: the shop settles an invoice in full.
        assertTrue(draft("a" to 1_000_000L).canSubmit(invoices))
        assertTrue(draft("a" to 1_000_000L).overrun(invoices).isEmpty())
    }

    @Test
    fun `overrun is measured against the balance, not the billed total`() {
        // Invoice b was billed 5,000,000 and has 500,000 left. Paying 600,000 is
        // an overpayment even though it is far below what was billed - and
        // checking against the total instead would have let it through.
        assertEquals(setOf("b"), draft("b" to 600_000L).overrun(invoices))
        assertTrue(draft("b" to 500_000L).overrun(invoices).isEmpty())
    }

    @Test
    fun `one bad line blocks the whole batch`() {
        // The batch is one transaction on the server, so letting the rep send it
        // would fail everything anyway - better to say which line before they try.
        val d = draft("a" to 500_000L, "b" to 600_000L)

        assertEquals(setOf("b"), d.overrun(invoices))
        assertFalse(d.canSubmit(invoices))
    }

    @Test
    fun `an amount against an unknown invoice is an overrun`() {
        // Defensive: the invoice list was re-read and this one is gone, settled by
        // someone else. Treating the missing balance as zero refuses the money
        // rather than sending it into a void.
        assertEquals(setOf("gone"), draft("gone" to 100L).overrun(invoices))
    }

    @Test
    fun `lateness is measured against the due date`() {
        val late = invoice("c", total = 100, dueOn = LocalDate.of(2026, 8, 10))
        val today = LocalDate.of(2026, 8, 17)

        assertTrue(late.isOverdue(today))
        assertEquals(7L, late.daysLate(today))
    }

    @Test
    fun `an invoice due today is not late yet`() {
        val due = invoice("d", total = 100, dueOn = LocalDate.of(2026, 8, 17))
        val today = LocalDate.of(2026, 8, 17)

        assertFalse(due.isOverdue(today))
        assertEquals(0L, due.daysLate(today))
    }
}
