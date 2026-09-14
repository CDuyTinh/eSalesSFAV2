package com.tinhcd.myesalessfa.feature.traderegis

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tinhcd.myesalessfa.domain.DataResult
import com.tinhcd.myesalessfa.domain.model.RegisterableCustomer
import com.tinhcd.myesalessfa.domain.model.TradeKind
import com.tinhcd.myesalessfa.domain.model.TradeProgramOffers
import com.tinhcd.myesalessfa.domain.model.TradeRegistration
import com.tinhcd.myesalessfa.domain.model.TradeRegistrationBoard
import com.tinhcd.myesalessfa.domain.model.TradeRegistrationStatus
import com.tinhcd.myesalessfa.domain.repository.TradeRegistrationRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Which page of the feature is on screen. */
enum class TradeRegisPage { LIST, PICK_CUSTOMER, OFFERS }

data class TradeRegisUiState(
    val loading: Boolean = true,
    val page: TradeRegisPage = TradeRegisPage.LIST,
    val board: TradeRegistrationBoard = TradeRegistrationBoard(),

    val kindFilter: TradeKind? = null,
    val statusFilter: TradeRegistrationStatus? = null,
    val programFilter: String? = null,
    val query: String = "",

    /** The outlets the picker is choosing from. */
    val customers: List<RegisterableCustomer> = emptyList(),
    val pickerQuery: String = "",
    val pickerLoading: Boolean = false,

    val chosen: RegisterableCustomer? = null,
    val offers: TradeProgramOffers = TradeProgramOffers(),
    val offersLoading: Boolean = false,

    val working: Boolean = false,
    val error: String? = null,
    val message: String? = null,
) {
    /**
     * The list as the chips leave it.
     *
     * Narrowed here rather than re-fetched: the kind and status filters are a
     * handful of rows either way, and a round trip per chip would make the
     * filter row feel like a form.
     */
    val visible: List<TradeRegistration>
        get() = board.items.filter { row ->
            (kindFilter == null || row.kind == kindFilter) &&
                (statusFilter == null || row.status == statusFilter) &&
                (programFilter == null || row.programId == programFilter)
        }

    val visibleCustomers: List<RegisterableCustomer>
        get() = if (pickerQuery.isBlank()) {
            customers
        } else {
            val needle = pickerQuery.trim().lowercase()
            customers.filter {
                it.name.lowercase().contains(needle) ||
                    it.code.lowercase().contains(needle)
            }
        }
}

/**
 * Đăng ký chương trình.
 *
 * The rebuild could already register an outlet for a display or loyalty
 * programme, but only from inside a call, one outlet at a time. A rep could not
 * see what they had signed up this quarter without walking every customer, and
 * could not sign anybody up without first checking in to them.
 *
 * This is the legacy's TRADE_REGIS: the list of everything registered, and a
 * way to register any outlet on the rep's route without a visit.
 */
@HiltViewModel
class TradeRegisViewModel @Inject constructor(
    private val repository: TradeRegistrationRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(TradeRegisUiState())
    val state: StateFlow<TradeRegisUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            // Unfiltered: the chips narrow what came back, and the counts beside
            // them have to keep counting everything.
            when (val result = repository.board(query = _state.value.query)) {
                is DataResult.Success -> _state.update {
                    it.copy(loading = false, board = result.data)
                }

                is DataResult.Failure -> _state.update {
                    it.copy(loading = false, error = "Không tải được danh sách đăng ký")
                }
            }
        }
    }

    /** Tapping the selected chip clears it. */
    fun onKind(kind: TradeKind?) = _state.update {
        it.copy(
            kindFilter = if (it.kindFilter == kind) null else kind,
            // A programme belongs to one kind, so a programme chip left over
            // from the other kind would filter the list to nothing.
            programFilter = null,
        )
    }

    fun onStatus(status: TradeRegistrationStatus?) = _state.update {
        it.copy(statusFilter = if (it.statusFilter == status) null else status)
    }

    fun onProgram(programId: String?) = _state.update {
        it.copy(programFilter = if (it.programFilter == programId) null else programId)
    }

    /** Searched server-side: a rep with hundreds of sign-ups is not sent them all. */
    fun onQuery(value: String) {
        _state.update { it.copy(query = value) }
        load()
    }

    fun dismissMessage() = _state.update { it.copy(message = null, error = null) }

    // -------------------------------------------------------------------------
    // Registering
    // -------------------------------------------------------------------------

    fun onOpenPicker() {
        _state.update {
            it.copy(page = TradeRegisPage.PICK_CUSTOMER, pickerQuery = "", pickerLoading = true)
        }

        viewModelScope.launch {
            when (val result = repository.customers()) {
                is DataResult.Success -> _state.update {
                    it.copy(pickerLoading = false, customers = result.data)
                }

                is DataResult.Failure -> _state.update {
                    it.copy(pickerLoading = false, error = "Không tải được danh sách khách hàng")
                }
            }
        }
    }

    fun onPickerQuery(value: String) = _state.update { it.copy(pickerQuery = value) }

    fun onPickCustomer(customer: RegisterableCustomer) {
        _state.update {
            it.copy(
                page = TradeRegisPage.OFFERS,
                chosen = customer,
                offers = TradeProgramOffers(),
                offersLoading = true,
                error = null,
            )
        }

        viewModelScope.launch {
            when (val result = repository.offersFor(customer.id)) {
                is DataResult.Success -> _state.update {
                    it.copy(offersLoading = false, offers = result.data)
                }

                is DataResult.Failure -> _state.update {
                    it.copy(offersLoading = false, error = "Không tải được chương trình")
                }
            }
        }
    }

    fun onBack() = _state.update {
        when (it.page) {
            TradeRegisPage.OFFERS -> it.copy(page = TradeRegisPage.PICK_CUSTOMER, error = null)
            else -> it.copy(page = TradeRegisPage.LIST, chosen = null, error = null)
        }
    }

    fun register(kind: TradeKind, programId: String, levelId: String) {
        val customer = _state.value.chosen ?: return
        if (_state.value.working) return

        _state.update { it.copy(working = true, error = null) }

        viewModelScope.launch {
            when (
                val result = repository.register(
                    kind = kind,
                    customerId = customer.id,
                    programId = programId,
                    levelId = levelId,
                )
            ) {
                is DataResult.Success -> {
                    _state.update {
                        it.copy(
                            working = false,
                            page = TradeRegisPage.LIST,
                            chosen = null,
                            message = "Đã đăng ký cho ${customer.name}",
                        )
                    }
                    load()
                }

                // The server's refusals are the interesting ones — no slots
                // left, already registered, outside the window — so its own
                // words are shown rather than a generic line.
                is DataResult.Failure -> _state.update {
                    it.copy(working = false, error = result.error.registerMessage())
                }
            }
        }
    }
}

private fun com.tinhcd.myesalessfa.domain.AppError.registerMessage(): String = when (this) {
    is com.tinhcd.myesalessfa.domain.AppError.Network -> "Không có kết nối mạng"
    is com.tinhcd.myesalessfa.domain.AppError.Auth -> "Phiên đăng nhập đã hết hạn, đăng nhập lại"
    is com.tinhcd.myesalessfa.domain.AppError.Server -> message ?: "Không đăng ký được"
    is com.tinhcd.myesalessfa.domain.AppError.Rule -> message ?: "Không đăng ký được"
    is com.tinhcd.myesalessfa.domain.AppError.Unknown -> message ?: "Không đăng ký được"
}
