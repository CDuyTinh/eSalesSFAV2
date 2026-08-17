package com.tinhcd.myesalessfa.feature.receivables

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tinhcd.myesalessfa.domain.AppError
import com.tinhcd.myesalessfa.domain.DataResult
import com.tinhcd.myesalessfa.domain.model.PaymentDraft
import com.tinhcd.myesalessfa.domain.model.ReceivableCustomer
import com.tinhcd.myesalessfa.domain.model.ReceivableInvoice
import com.tinhcd.myesalessfa.domain.repository.ReceivableRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

data class ReceivablesUiState(
    val loading: Boolean = true,
    val customers: List<ReceivableCustomer> = emptyList(),
    val query: String = "",
    /** Set while an outlet's invoices are open. Null on the list. */
    val open: OpenCustomer? = null,
    val error: String? = null,
) {
    val visible: List<ReceivableCustomer>
        get() {
            val needle = query.trim()
            if (needle.isEmpty()) return customers
            return customers.filter {
                it.customerName.contains(needle, ignoreCase = true) ||
                    it.customerCode.contains(needle, ignoreCase = true)
            }
        }

    val totalOutstanding: Long get() = customers.sumOf { it.outstanding }

    val overdueCount: Int get() = customers.count { it.overdue }
}

data class OpenCustomer(
    val customer: ReceivableCustomer,
    val invoices: List<ReceivableInvoice> = emptyList(),
    val draft: PaymentDraft,
    val loading: Boolean = true,
    val submitting: Boolean = false,
    val error: String? = null,
    val collected: Boolean = false,
) {
    val overrun: Set<String> get() = draft.overrun(invoices)

    val canSubmit: Boolean get() = !submitting && draft.canSubmit(invoices)
}

@HiltViewModel
class ReceivablesViewModel @Inject constructor(
    private val repository: ReceivableRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(ReceivablesUiState())
    val state: StateFlow<ReceivablesUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            when (val result = repository.customers()) {
                is DataResult.Success ->
                    _state.update { it.copy(loading = false, customers = result.data) }

                is DataResult.Failure ->
                    _state.update { it.copy(loading = false, error = "Không tải được công nợ") }
            }
        }
    }

    fun onQueryChanged(query: String) = _state.update { it.copy(query = query) }

    /**
     * The batch id is minted here, once, when the rep opens the outlet — not when
     * they press save. That is what makes a second press a replay rather than a
     * second collection.
     */
    fun openCustomer(customer: ReceivableCustomer) {
        _state.update {
            it.copy(
                open = OpenCustomer(
                    customer = customer,
                    draft = PaymentDraft(
                        customerId = customer.customerId,
                        batchId = UUID.randomUUID().toString(),
                    ),
                ),
            )
        }
        loadInvoices(customer.customerId)
    }

    fun closeCustomer() = _state.update { it.copy(open = null) }

    fun onAmountChanged(invoiceId: String, amount: Long) {
        updateOpen { open ->
            val amounts = open.draft.amounts.toMutableMap()
            if (amount <= 0) amounts.remove(invoiceId) else amounts[invoiceId] = amount
            open.copy(draft = open.draft.copy(amounts = amounts), error = null)
        }
    }

    /** Fills the invoice with exactly what is left on it, which is the common case. */
    fun payInFull(invoice: ReceivableInvoice) =
        onAmountChanged(invoice.invoiceId, invoice.outstanding)

    fun onNoteChanged(note: String) =
        updateOpen { it.copy(draft = it.draft.copy(note = note)) }

    fun submit() {
        val open = _state.value.open ?: return
        if (!open.canSubmit) return

        updateOpen { it.copy(submitting = true, error = null) }

        viewModelScope.launch {
            when (val result = repository.collect(open.draft, visitId = null)) {
                is DataResult.Success -> {
                    updateOpen { it.copy(submitting = false, collected = true) }
                    // Re-read both sides. The balance the rep now sees is what was
                    // actually recorded, which matters most in the one case this
                    // could go wrong: a save that succeeded, timed out, and was
                    // sent again with a corrected figure that the server treated
                    // as a replay.
                    loadInvoices(open.customer.customerId)
                    load()
                }

                is DataResult.Failure ->
                    updateOpen {
                        it.copy(submitting = false, error = result.error.collectMessage())
                    }
            }
        }
    }

    private fun loadInvoices(customerId: String) {
        viewModelScope.launch {
            when (val result = repository.invoices(customerId)) {
                is DataResult.Success ->
                    updateOpen { it.copy(loading = false, invoices = result.data) }

                is DataResult.Failure ->
                    updateOpen {
                        it.copy(loading = false, error = "Không tải được danh sách hoá đơn")
                    }
            }
        }
    }

    private fun updateOpen(change: (OpenCustomer) -> OpenCustomer) =
        _state.update { it.copy(open = it.open?.let(change)) }
}

/**
 * The trigger's refusal names the invoice's remaining balance, which is the one
 * figure that lets the rep fix what they typed. Anything this screen said in its
 * place would be worse.
 */
private fun AppError.collectMessage(): String = when (this) {
    is AppError.Network -> "Không có kết nối mạng"
    is AppError.Auth -> "Phiên đăng nhập đã hết hạn, đăng nhập lại"
    is AppError.Server -> message.orFallback()
    is AppError.Rule -> message.orFallback()
    is AppError.Unknown -> message.orFallback()
}

private fun String?.orFallback(): String =
    this?.takeIf { it.isNotBlank() }?.replaceFirstChar { it.uppercase() }
        ?: "Chưa ghi nhận được số tiền thu, thử lại"
