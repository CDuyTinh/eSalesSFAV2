package com.tinhcd.myesalessfa.feature.checkin

import com.tinhcd.myesalessfa.core.photo.PhotoStore
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
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
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
    /**
     * The photo the check-in sends: the shop front with the rep's selfie set
     * into it and the stamp on top, on the device until the check-in takes it up.
     */
    val photoLocalPath: String? = null,
    /** The two shots it was made from, kept to show on the screen. */
    val shopPhotoPath: String? = null,
    val selfiePhotoPath: String? = null,
    /** The two-lens camera is open. */
    val cameraOpen: Boolean = false,
    val capturing: Boolean = false,
    /** Head office wants the shop front before the visit starts. */
    val photoRequired: Boolean = false,
    val submitting: Boolean = false,
    val error: String? = null,
    val finished: Boolean = false,
    val queuedOffline: Boolean = false,
) {
    /** A reason is required whenever the gate is not clean. */
    val needsReason: Boolean get() = gate is CheckInGate.NeedsReason

    val canSubmit: Boolean
        get() = !submitting && !locating && !capturing &&
            (!photoRequired || photoLocalPath != null) &&
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
    private val photoStore: PhotoStore,
) : ViewModel() {

    private val customerId: String = checkNotNull(savedStateHandle["customerId"])

    private var policy: CheckInPolicy = CheckInPolicy.Fallback

    private val _state = MutableStateFlow(CheckInUiState())
    val state: StateFlow<CheckInUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            policy = configRepository.checkInPolicy()
            _state.update { it.copy(photoRequired = policy.photoRequired) }
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

    fun openCamera() = _state.update { it.copy(cameraOpen = true, error = null) }

    fun closeCamera() = _state.update { it.copy(cameraOpen = false) }

    fun newPhotoFile(): String = photoStore.newFile()

    /**
     * Both shots are in: they are shrunk, the selfie is set into the shop front
     * with the stamp, and that one file becomes the check-in photo. A retake
     * replaces all three; the ones it replaces are deleted from the phone.
     */
    fun onPhotosTaken(shopPath: String, selfiePath: String?) {
        _state.update { it.copy(cameraOpen = false, capturing = true, error = null) }
        viewModelScope.launch {
            val shots = listOfNotNull(shopPath, selfiePath)
            val merged = if (shots.all { photoStore.compress(it) > 0L }) {
                photoStore.composeCheckIn(shopPath, selfiePath, stamp())
            } else {
                null
            }
            if (merged == null) {
                shots.forEach(photoStore::delete)
                _state.update { it.copy(capturing = false, error = "Không lưu được ảnh, thử lại") }
                return@launch
            }
            discardPhotos()
            _state.update {
                it.copy(
                    capturing = false,
                    photoLocalPath = merged,
                    shopPhotoPath = shopPath,
                    selfiePhotoPath = selfiePath,
                )
            }
        }
    }

    fun removePhoto() {
        discardPhotos()
        _state.update { it.copy(photoLocalPath = null, shopPhotoPath = null, selfiePhotoPath = null) }
    }

    private fun discardPhotos() = _state.value.run {
        listOfNotNull(photoLocalPath, shopPhotoPath, selfiePhotoPath).forEach(photoStore::delete)
    }

    /** What the legacy writes on the check-in photo, in its words. */
    private fun stamp(): List<String> {
        val s = _state.value
        val c = s.customer
        fun coords(lat: Double?, lng: Double?) =
            if (lat == null || lng == null) "không có" else "%.6f, %.6f".format(Locale.US, lat, lng)
        return listOfNotNull(
            c?.let { " -KH: ${it.code} - ${it.name}" },
            " -Check in: ${LocalDateTime.now().format(stampTime)}",
            " -Toạ độ KH: ${coords(c?.lat, c?.lng)}",
            " -Toạ độ hiện tại: ${coords(s.location?.lat, s.location?.lng)}",
            c?.address?.takeIf { it.isNotBlank() }?.let { " -Địa chỉ KH: $it" },
        )
    }

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
                photoLocalPath = current.photoLocalPath,
                reasonId = current.selectedReason?.id,
                // Blank is not a note. Sending "" would fill a column someone
                // will later query for "check-ins that came with a note".
                note = current.note.trim().takeIf { it.isNotEmpty() },
            )

            when (val result = checkInRepository.checkIn(request)) {
                is DataResult.Success -> {
                    // The check-in now owns the merged photo; the two shots it
                    // was made from have nothing left to do.
                    listOfNotNull(current.shopPhotoPath, current.selfiePhotoPath).forEach(photoStore::delete)
                    _state.update {
                        it.copy(submitting = false, finished = true)
                    }
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

private val stampTime = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss")
