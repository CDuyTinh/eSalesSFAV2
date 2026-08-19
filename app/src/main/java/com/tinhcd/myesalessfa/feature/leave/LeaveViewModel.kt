package com.tinhcd.myesalessfa.feature.leave

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tinhcd.myesalessfa.domain.AppError
import com.tinhcd.myesalessfa.domain.DataResult
import com.tinhcd.myesalessfa.domain.model.LeaveDraft
import com.tinhcd.myesalessfa.domain.model.LeaveRequest
import com.tinhcd.myesalessfa.domain.model.LeaveType
import com.tinhcd.myesalessfa.domain.repository.LeaveRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

data class LeaveUiState(
    val loading: Boolean = true,
    val types: List<LeaveType> = emptyList(),
    val requests: List<LeaveRequest> = emptyList(),
    /** Non-null while the form is open. */
    val draft: LeaveDraft? = null,
    /** The request a withdrawal is being confirmed for. */
    val withdrawing: LeaveRequest? = null,
    val busy: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class LeaveViewModel @Inject constructor(
    private val repository: LeaveRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(LeaveUiState())
    val state: StateFlow<LeaveUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            when (val result = repository.board()) {
                is DataResult.Success -> _state.update {
                    it.copy(
                        loading = false,
                        types = result.data.types,
                        requests = result.data.requests,
                    )
                }

                is DataResult.Failure ->
                    _state.update { it.copy(loading = false, error = "Không tải được đơn nghỉ") }
            }
        }
    }

    fun startAdding() {
        // Tomorrow rather than today: leave is asked for in advance, and a rep who
        // means today can move it back in one tap.
        val tomorrow = LocalDate.now().plusDays(1)
        _state.update {
            it.copy(
                draft = LeaveDraft(fromDate = tomorrow, toDate = tomorrow),
                error = null,
            )
        }
    }

    fun cancelAdding() = _state.update { it.copy(draft = null) }

    fun onType(type: LeaveType) = editDraft { it.copy(leaveTypeId = type.leaveTypeId) }

    fun onReason(value: String) = editDraft { it.copy(reason = value) }

    /** Moving the start past the end drags the end with it, rather than refusing. */
    fun onFrom(date: LocalDate) = editDraft {
        val to = it.toDate
        it.copy(fromDate = date, toDate = if (to == null || to.isBefore(date)) date else to)
    }

    fun onTo(date: LocalDate) = editDraft { it.copy(toDate = date) }

    fun submit() {
        val draft = _state.value.draft ?: return
        if (_state.value.busy || !draft.canSubmit) return

        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            when (val result = repository.submit(draft)) {
                is DataResult.Success -> {
                    _state.update { it.copy(busy = false, draft = null) }
                    load()
                }

                // Kept on screen with the message. An overlap is something the rep
                // fixes by changing the dates, and closing the form would make
                // them type the whole thing again.
                is DataResult.Failure ->
                    _state.update { it.copy(busy = false, error = result.error.leaveMessage()) }
            }
        }
    }

    fun startWithdrawing(request: LeaveRequest) =
        _state.update { it.copy(withdrawing = request, error = null) }

    fun cancelWithdrawing() = _state.update { it.copy(withdrawing = null) }

    fun confirmWithdrawing() {
        val request = _state.value.withdrawing ?: return
        if (_state.value.busy) return

        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            when (val result = repository.withdraw(request.requestId)) {
                is DataResult.Success -> {
                    _state.update { it.copy(busy = false, withdrawing = null) }
                    load()
                }

                is DataResult.Failure -> {
                    _state.update {
                        it.copy(
                            busy = false,
                            withdrawing = null,
                            error = result.error.leaveMessage(),
                        )
                    }
                    // Somebody ruled on it while the rep was looking at it, so the
                    // list on screen is out of date either way.
                    load()
                }
            }
        }
    }

    private fun editDraft(change: (LeaveDraft) -> LeaveDraft) =
        _state.update { it.copy(draft = it.draft?.let(change), error = null) }
}

/**
 * The server's refusals here are the specific ones — an overlapping period, a
 * request already decided — and both tell the rep what to do next.
 */
private fun AppError.leaveMessage(): String = when (this) {
    is AppError.Network -> "Không có kết nối mạng"
    is AppError.Auth -> "Phiên đăng nhập đã hết hạn, đăng nhập lại"
    is AppError.Server -> message.orFallback()
    is AppError.Rule -> message.orFallback()
    is AppError.Unknown -> message.orFallback()
}

private fun String?.orFallback(): String =
    this?.takeIf { it.isNotBlank() }?.replaceFirstChar { it.uppercase() }
        ?: "Chưa gửi được đơn, thử lại"
