package com.tinhcd.myesalessfa.feature.dailytarget

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tinhcd.myesalessfa.domain.AppError
import com.tinhcd.myesalessfa.domain.DataResult
import com.tinhcd.myesalessfa.domain.model.DailyTargetPlan
import com.tinhcd.myesalessfa.domain.model.DailyTargetStop
import com.tinhcd.myesalessfa.domain.repository.DailyTargetRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

data class DailyTargetUiState(
    val loading: Boolean = true,
    val plan: DailyTargetPlan = DailyTargetPlan(date = LocalDate.now()),
    val saving: Boolean = false,
    val saved: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class DailyTargetViewModel @Inject constructor(
    private val repository: DailyTargetRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(DailyTargetUiState())
    val state: StateFlow<DailyTargetUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            val date = _state.value.plan.date
            when (val result = repository.stops(date)) {
                is DataResult.Success -> _state.update {
                    // Edits are dropped on a reload, which only happens after a
                    // save or a retry. Keeping them would leave the rep looking at
                    // figures that are no longer pending against rows that are.
                    it.copy(
                        loading = false,
                        plan = DailyTargetPlan(date = date, stops = result.data),
                    )
                }

                is DataResult.Failure -> _state.update {
                    it.copy(loading = false, error = "Không tải được chỉ tiêu ngày")
                }
            }
        }
    }

    fun onAmountChanged(customerId: String, amount: Long) {
        _state.update { current ->
            current.copy(
                plan = current.plan.copy(
                    edits = current.plan.edits + (customerId to amount.coerceAtLeast(0)),
                ),
                saved = false,
                error = null,
            )
        }
    }

    /** Fills an outlet with what the rep last sold there. */
    fun useSuggestion(stop: DailyTargetStop) {
        val suggested = _state.value.plan.suggestionFor(stop) ?: return
        onAmountChanged(stop.customerId, suggested)
    }

    fun save() {
        val plan = _state.value.plan
        if (_state.value.saving || !plan.canSave) return

        _state.update { it.copy(saving = true, error = null, saved = false) }

        viewModelScope.launch {
            when (val result = repository.save(plan.date, plan.changed)) {
                is DataResult.Success -> {
                    _state.update { it.copy(saving = false, saved = true) }
                    // Re-read so the figures on screen are the ones that landed,
                    // not the ones that were sent.
                    load()
                }

                is DataResult.Failure -> _state.update {
                    it.copy(saving = false, error = result.error.saveMessage())
                }
            }
        }
    }
}

private fun AppError.saveMessage(): String = when (this) {
    is AppError.Network -> "Không có kết nối mạng"
    is AppError.Auth -> "Phiên đăng nhập đã hết hạn, đăng nhập lại"
    is AppError.Server -> message.orFallback()
    is AppError.Rule -> message.orFallback()
    is AppError.Unknown -> message.orFallback()
}

private fun String?.orFallback(): String =
    this?.takeIf { it.isNotBlank() }?.replaceFirstChar { it.uppercase() }
        ?: "Chưa lưu được chỉ tiêu, thử lại"
