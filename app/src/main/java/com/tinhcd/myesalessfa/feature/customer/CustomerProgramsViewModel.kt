package com.tinhcd.myesalessfa.feature.customer

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tinhcd.myesalessfa.domain.DataResult
import com.tinhcd.myesalessfa.domain.model.DisplayProgram
import com.tinhcd.myesalessfa.domain.model.DisplayProgramOffer
import com.tinhcd.myesalessfa.domain.repository.DisplayAuditRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class CustomerProgramsUiState(
    val loading: Boolean = true,
    /** What the outlet is already signed up for. Empty without an open visit. */
    val joined: List<DisplayProgram> = emptyList(),
    /** What it could still join today, with this rep's slots at each level. */
    val open: List<DisplayProgramOffer> = emptyList(),
    /** Which programme's levels are expanded, by id. */
    val expanded: String? = null,
    val registering: Boolean = false,
    val error: String? = null,
    val justRegistered: String? = null,
) {
    val isEmpty: Boolean get() = !loading && joined.isEmpty() && open.isEmpty()
}

/**
 * Chương trình — what this outlet is in, and what it could still join.
 *
 * The tab used to say "chưa có trong bản này", which was true when nothing
 * modelled a programme. Display programmes are modelled now, and signing an
 * outlet up is the rep's job: TradeRegis over there, whose whole screen is this
 * list plus a level to pick.
 *
 * Loyalty and POSM are the legacy tab's other two families. POSM has a step of
 * its own here; loyalty has no schema yet, and the tab says so rather than
 * implying this is the whole picture.
 */
@HiltViewModel
class CustomerProgramsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val displayRepository: DisplayAuditRepository,
) : ViewModel() {

    private val customerId: String = checkNotNull(savedStateHandle["customerId"])

    /**
     * Null outside a visit. The outlet's screen is reachable before check-in —
     * that is most of its value — but a registration belongs to the call it was
     * agreed on, so without one the list is readable and the button is not there.
     */
    private val visitId: String? = savedStateHandle["visitId"]

    private val _state = MutableStateFlow(CustomerProgramsUiState())
    val state: StateFlow<CustomerProgramsUiState> = _state.asStateFlow()

    val canRegister: Boolean get() = visitId != null

    init {
        load()
    }

    fun load() {
        _state.update { it.copy(loading = true, error = null) }

        viewModelScope.launch {
            // What the outlet is in comes from the audit listing, which is keyed by
            // the visit. Outside a call there is nothing to key it to, so only the
            // half that does not need one is fetched.
            val joined = visitId?.let {
                (displayRepository.programs(customerId, it) as? DataResult.Success)?.data
            }.orEmpty()

            when (val open = displayRepository.openPrograms(customerId)) {
                is DataResult.Success -> _state.update {
                    it.copy(loading = false, joined = joined, open = open.data)
                }

                is DataResult.Failure -> _state.update {
                    it.copy(
                        loading = false,
                        joined = joined,
                        error = "Không tải được danh sách chương trình",
                    )
                }
            }
        }
    }

    /** Tapping the open programme again folds it back up. */
    fun onToggle(programId: String) = _state.update {
        it.copy(expanded = if (it.expanded == programId) null else programId, error = null)
    }

    fun onRegister(programId: String, levelId: String) {
        val visit = visitId ?: return
        if (_state.value.registering) return

        _state.update { it.copy(registering = true, error = null) }

        viewModelScope.launch {
            when (displayRepository.register(visit, programId, levelId)) {
                is DataResult.Success -> {
                    // Reloaded rather than patched in place: the programme leaves
                    // the open list and a slot leaves the rep's allocation, and
                    // both are the server's arithmetic to report.
                    _state.update {
                        it.copy(registering = false, expanded = null, justRegistered = programId)
                    }
                    load()
                }

                is DataResult.Failure -> _state.update {
                    it.copy(
                        registering = false,
                        // The server names the reason — closed window, no slots,
                        // already in it — and those are exactly what the rep needs
                        // to hear, so the message is not flattened to "lỗi".
                        error = "Không đăng ký được. Kiểm tra hạn đăng ký và số suất còn lại.",
                    )
                }
            }
        }
    }

    fun onDismissJustRegistered() = _state.update { it.copy(justRegistered = null) }
}
