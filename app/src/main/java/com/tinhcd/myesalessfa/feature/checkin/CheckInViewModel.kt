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
    /**
     * Free text the rep types at the door. [CheckInRequest] has carried this
     * field since the first version and was being sent null — the screen simply
     * never asked for it.
     */
    val note: String = "",
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
                        it.copy(loading = false, error = "Không tải được thông tin khách hàng")
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

    fun onNoteChange(value: String) = _state.update { it.copy(note = value) }

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
                // Blank is not a note. Sending "" would fill a column someone
                // will later query for "check-ins that came with a note".
                note = current.note.trim().takeIf { it.isNotEmpty() },
            )

            when (val result = checkInRepository.checkIn(request)) {
                is DataResult.Success ->
                    _state.update {
                        it.copy(submitting = false, finished = true)
                    }

                is DataResult.Failure ->
                    _state.update {
                        it.copy(submitting = false, error = "Không lưu được check-in")
                    }
            }
        }
    }
}

internal fun ReasonKind.prompt(): String = when (this) {
    ReasonKind.GPS_OUT_OF_RANGE -> "Bạn đang ở ngoài bán kính cho phép. Chọn lý do để tiếp tục:"
    ReasonKind.GPS_LOW_ACCURACY -> "Tín hiệu GPS không đủ chính xác. Chọn lý do để tiếp tục:"
    ReasonKind.GPS_UNAVAILABLE -> "Không lấy được vị trí. Chọn lý do để tiếp tục:"
    ReasonKind.OUTLET_CLOSED -> "Cửa hàng đóng cửa. Chọn lý do:"
    ReasonKind.NO_ORDER -> "Khách không đặt hàng. Chọn lý do:"
    ReasonKind.PHOTO_SKIPPED -> "Không chụp được ảnh. Chọn lý do:"

    // Not a check-in gate at all — it classifies what feedback is about, and no gate
    // resolves to it. A neutral string rather than an exception, because an
    // unreachable branch is not worth crashing a rep's check-in over.
    ReasonKind.FEEDBACK_TOPIC -> "Chọn lý do:"
}
