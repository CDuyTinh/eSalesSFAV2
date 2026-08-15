package com.tinhcd.myesalessfa.feature.checkin

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tinhcd.myesalessfa.core.location.LocationProvider
import com.tinhcd.myesalessfa.domain.DataResult
import com.tinhcd.myesalessfa.domain.model.CheckInGate
import com.tinhcd.myesalessfa.domain.model.CheckInPolicy
import com.tinhcd.myesalessfa.domain.model.CheckInRequest
import com.tinhcd.myesalessfa.domain.model.Customer
import com.tinhcd.myesalessfa.domain.model.GeoPoint
import com.tinhcd.myesalessfa.domain.model.ReasonCode
import com.tinhcd.myesalessfa.domain.model.ReasonKind
import com.tinhcd.myesalessfa.domain.model.RouteStop
import com.tinhcd.myesalessfa.domain.repository.CheckInRepository
import com.tinhcd.myesalessfa.domain.repository.ConfigRepository
import com.tinhcd.myesalessfa.domain.repository.RouteRepository
import com.tinhcd.myesalessfa.domain.repository.SubmitOutcome
import com.tinhcd.myesalessfa.domain.usecase.EvaluateCheckInUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

data class CheckInUiState(
    val loading: Boolean = true,
    val customer: Customer? = null,
    val stop: RouteStop? = null,
    val location: GeoPoint? = null,
    val locating: Boolean = false,
    val gate: CheckInGate? = null,
    val reasons: List<ReasonCode> = emptyList(),
    val selectedReason: ReasonCode? = null,
    val submitting: Boolean = false,
    val error: String? = null,
    val finished: Boolean = false,
    val queuedOffline: Boolean = false,
) {
    /** A reason is required whenever the gate is not clean. */
    val needsReason: Boolean get() = gate is CheckInGate.NeedsReason

    val canSubmit: Boolean
        get() = !submitting && !locating &&
            when (gate) {
                is CheckInGate.Allowed -> true
                is CheckInGate.NeedsReason -> selectedReason != null
                else -> false
            }
}

@HiltViewModel
class CheckInViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val routeRepository: RouteRepository,
    private val checkInRepository: CheckInRepository,
    private val configRepository: ConfigRepository,
    private val evaluate: EvaluateCheckInUseCase,
    private val locationProvider: LocationProvider,
) : ViewModel() {

    private val customerId: String = checkNotNull(savedStateHandle["customerId"])

    private var policy: CheckInPolicy = CheckInPolicy.Fallback

    private val _state = MutableStateFlow(CheckInUiState())
    val state: StateFlow<CheckInUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            policy = configRepository.checkInPolicy()
            when (val result = routeRepository.getStop(customerId, LocalDate.now())) {
                is DataResult.Success -> {
                    _state.update {
                        it.copy(
                            loading = false,
                            stop = result.data,
                            customer = result.data?.customer,
                        )
                    }
                    if (result.data != null) refreshLocation()
                }

                is DataResult.Failure ->
                    _state.update {
                        it.copy(loading = false, error = "Khong tai duoc thong tin khach hang")
                    }
            }
        }
    }

    fun refreshLocation() {
        val customer = _state.value.customer ?: return
        _state.update { it.copy(locating = true, error = null) }

        viewModelScope.launch {
            val point = runCatching { locationProvider.currentLocation() }.getOrNull()
            val gate = evaluate(customer, point, policy)

            // Only load the reason list the situation actually calls for, so
            // the rep is not scrolling past irrelevant options.
            val reasons = when (gate) {
                is CheckInGate.NeedsReason -> configRepository.reasons(gate.kind)
                else -> emptyList<ReasonCode>()
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
        val customer = current.customer ?: return
        val point = current.location
            ?: GeoPoint(0.0, 0.0, null) // GPS_UNAVAILABLE path: recorded with a reason.

        if (!current.canSubmit) return
        _state.update { it.copy(submitting = true, error = null) }

        viewModelScope.launch {
            val request = CheckInRequest(
                customerId = customer.id,
                point = point,
                distanceM = when (val g = current.gate) {
                    is CheckInGate.Allowed -> g.distanceM
                    is CheckInGate.NeedsReason -> g.distanceM ?: 0.0
                    else -> 0.0
                },
                photoPath = null,
                reasonCode = current.selectedReason?.code,
                note = null,
            )

            when (val result = checkInRepository.checkIn(request)) {
                is DataResult.Success ->
                    _state.update {
                        it.copy(
                            submitting = false,
                            finished = true,
                            queuedOffline = result.data == SubmitOutcome.QUEUED,
                        )
                    }

                is DataResult.Failure ->
                    _state.update {
                        it.copy(submitting = false, error = "Khong luu duoc check-in")
                    }
            }
        }
    }
}

internal fun ReasonKind.prompt(): String = when (this) {
    ReasonKind.GPS_OUT_OF_RANGE -> "Ban dang o ngoai ban kinh cho phep. Chon ly do de tiep tuc:"
    ReasonKind.GPS_LOW_ACCURACY -> "Tin hieu GPS khong du chinh xac. Chon ly do de tiep tuc:"
    ReasonKind.GPS_UNAVAILABLE -> "Khong lay duoc vi tri. Chon ly do de tiep tuc:"
    ReasonKind.OUTLET_CLOSED -> "Cua hang dong cua. Chon ly do:"
    ReasonKind.NO_ORDER -> "Khach khong dat hang. Chon ly do:"
    ReasonKind.PHOTO_SKIPPED -> "Khong chup duoc anh. Chon ly do:"

    // Not a check-in gate at all — it classifies what feedback is about, and no gate
    // resolves to it. A neutral string rather than an exception, because an
    // unreachable branch is not worth crashing a rep's check-in over.
    ReasonKind.FEEDBACK_TOPIC -> "Chon ly do:"
}
