package com.tinhcd.myesalessfa.feature.workday

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tinhcd.myesalessfa.core.location.LocationProvider
import com.tinhcd.myesalessfa.domain.AppError
import com.tinhcd.myesalessfa.domain.DataResult
import com.tinhcd.myesalessfa.domain.model.CheckInGate
import com.tinhcd.myesalessfa.domain.model.GeoPoint
import com.tinhcd.myesalessfa.domain.model.ReasonCode
import com.tinhcd.myesalessfa.domain.model.WorkDay
import com.tinhcd.myesalessfa.domain.model.WorkDayPolicy
import com.tinhcd.myesalessfa.domain.model.WorkDayPunch
import com.tinhcd.myesalessfa.domain.model.WorkDayState
import com.tinhcd.myesalessfa.domain.repository.ConfigRepository
import com.tinhcd.myesalessfa.domain.repository.TimekeepingRepository
import com.tinhcd.myesalessfa.domain.usecase.EvaluateCheckInUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

data class WorkDayUiState(
    val loading: Boolean = true,
    val day: WorkDay? = null,
    val policy: WorkDayPolicy = WorkDayPolicy.Fallback,
    val location: GeoPoint? = null,
    val locating: Boolean = false,
    val gate: CheckInGate? = null,
    val reasons: List<ReasonCode> = emptyList(),
    val selectedReason: ReasonCode? = null,
    val submitting: Boolean = false,
    val error: String? = null,
    val finished: Boolean = false,
) {
    val needsReason: Boolean get() = gate is CheckInGate.NeedsReason

    /**
     * Which punch this visit to the depot is for, decided by the day itself
     * rather than by how the rep got here. Arriving at a closed day from a stale
     * screen should not offer to close it again.
     */
    val closing: Boolean get() = day?.state == WorkDayState.OPEN

    val canSubmit: Boolean
        get() = !submitting && !locating && day != null &&
            day.state != WorkDayState.CLOSED &&
            // A depot cannot be left while the rep is still inside a shop.
            (!closing || day.canCloseDay) &&
            when (gate) {
                is CheckInGate.Allowed -> true
                is CheckInGate.NeedsReason -> selectedReason != null
                else -> false
            }
}

/**
 * Opening and closing the selling day.
 *
 * One screen for both punches: they ask the same question of the same place, and
 * splitting them would have produced two copies of the GPS gate that differed in
 * a month's time.
 */
@HiltViewModel
class WorkDayViewModel @Inject constructor(
    private val timekeeping: TimekeepingRepository,
    private val configRepository: ConfigRepository,
    private val evaluate: EvaluateCheckInUseCase,
    private val locationProvider: LocationProvider,
) : ViewModel() {

    private val _state = MutableStateFlow(WorkDayUiState())
    val state: StateFlow<WorkDayUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            val policy = configRepository.workDayPolicy()
            when (val result = timekeeping.refresh(LocalDate.now())) {
                is DataResult.Success -> {
                    _state.update {
                        it.copy(loading = false, day = result.data, policy = policy)
                    }
                    refreshLocation()
                }

                is DataResult.Failure ->
                    _state.update {
                        it.copy(
                            loading = false,
                            policy = policy,
                            error = "Không tải được thông tin chấm công",
                        )
                    }
            }
        }
    }

    fun refreshLocation() {
        val day = _state.value.day ?: return
        _state.update { it.copy(locating = true, error = null) }

        viewModelScope.launch {
            val point = runCatching { locationProvider.currentLocation() }.getOrNull()
            val gate = evaluate.atBranch(day.branch, point, _state.value.policy)

            val reasons = when (gate) {
                is CheckInGate.NeedsReason -> configRepository.reasons(gate.kind)
                else -> emptyList()
            }

            _state.update {
                it.copy(
                    locating = false,
                    location = point,
                    gate = gate,
                    reasons = reasons,
                    selectedReason = null,
                )
            }
        }
    }

    fun selectReason(reason: ReasonCode) = _state.update { it.copy(selectedReason = reason) }

    fun submit() {
        val current = _state.value
        val day = current.day ?: return
        if (!current.canSubmit) return

        _state.update { it.copy(submitting = true, error = null) }

        viewModelScope.launch {
            val punch = WorkDayPunch(
                date = day.date,
                point = current.location,
                distanceM = when (val g = current.gate) {
                    is CheckInGate.Allowed -> g.distanceM
                    is CheckInGate.NeedsReason -> g.distanceM
                    else -> null
                },
                reasonId = current.selectedReason?.id,
            )

            val result = if (current.closing) {
                timekeeping.closeDay(punch)
            } else {
                timekeeping.openDay(punch)
            }

            when (result) {
                is DataResult.Success -> _state.update {
                    it.copy(submitting = false, finished = true)
                }

                // The server's own words, not a generic failure: its refusals are
                // specific and actionable — the day is already open, or two visits
                // are still running — and a rep standing in the depot can act on
                // that. `AppError.Server` carries the message the function raised.
                is DataResult.Failure -> _state.update {
                    it.copy(submitting = false, error = result.error.punchMessage())
                }
            }
        }
    }
}

/**
 * Prefers what the server said over anything this screen could invent.
 *
 * "Còn 2 cuộc viếng thăm chưa check-out" tells a rep exactly what to go and do.
 * "Không chấm công được" tells them to phone somebody. The generic strings here
 * are only for the failures that carry no message of their own.
 */
private fun AppError.punchMessage(): String = when (this) {
    is AppError.Network -> "Không có kết nối mạng"
    is AppError.Auth -> "Phiên đăng nhập đã hết hạn, đăng nhập lại"
    is AppError.Server -> message.orFallback()
    is AppError.Rule -> message.orFallback()
    is AppError.Unknown -> message.orFallback()
}

private fun String?.orFallback(): String =
    this?.takeIf { it.isNotBlank() }?.replaceFirstChar { it.uppercase() }
        ?: "Chưa chấm công được, thử lại"
