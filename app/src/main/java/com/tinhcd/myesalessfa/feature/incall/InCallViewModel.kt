package com.tinhcd.myesalessfa.feature.incall

import androidx.lifecycle.SavedStateHandle
import com.tinhcd.myesalessfa.core.ui.uiText
import com.tinhcd.myesalessfa.core.ui.UiText
import com.tinhcd.myesalessfa.R
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tinhcd.myesalessfa.core.photo.PhotoStore
import com.tinhcd.myesalessfa.core.photo.PhotoTarget
import com.tinhcd.myesalessfa.domain.DataResult
import com.tinhcd.myesalessfa.domain.model.Customer
import com.tinhcd.myesalessfa.domain.model.VisitWorkflow
import com.tinhcd.myesalessfa.domain.repository.CheckInRepository
import com.tinhcd.myesalessfa.domain.repository.ConfigRepository
import com.tinhcd.myesalessfa.domain.repository.RouteRepository
import com.tinhcd.myesalessfa.domain.repository.WorkflowRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

data class InCallUiState(
    val loading: Boolean = true,
    val customer: Customer? = null,
    val workflow: VisitWorkflow? = null,
    val submitting: Boolean = false,
    val error: UiText? = null,
    val checkedOut: Boolean = false,
    /** The check-out photo, on the device until the check-out takes it up. */
    val photoLocalPath: String? = null,
    val capturing: Boolean = false,
    /** `checkout_photo_required`. */
    val photoRequired: Boolean = false,
) {
    /** The steps allow it, and the photo is there when one is required. */
    val canCheckOut: Boolean
        get() = workflow?.canCheckOut == true && !submitting && !capturing &&
            (!photoRequired || photoLocalPath != null)
}

/**
 * Drives the in-call screen. Notably it has no idea what the steps are — the
 * list comes from `sales_step` on the server, so enabling a new step for a
 * market is a data change rather than a release.
 */
@HiltViewModel
class InCallViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val workflowRepository: WorkflowRepository,
    private val routeRepository: RouteRepository,
    private val checkInRepository: CheckInRepository,
    private val configRepository: ConfigRepository,
    private val photoStore: PhotoStore,
) : ViewModel() {

    private val visitId: String = checkNotNull(savedStateHandle["visitId"])
    private val customerId: String = checkNotNull(savedStateHandle["customerId"])

    private val _state = MutableStateFlow(InCallUiState())
    val state: StateFlow<InCallUiState> = _state.asStateFlow()

    private var pendingPhoto: PhotoTarget? = null

    // No initial load in an init block: the screen calls load() on every entry,
    // which covers the first one too. Loading in both places just fetched twice.

    /** Called again on return from a step so completions show immediately. */
    fun load() {
        _state.update { it.copy(loading = it.workflow == null, error = null) }
        viewModelScope.launch {
            val photoRequired = configRepository.checkInPolicy().checkOutPhotoRequired
            _state.update { it.copy(photoRequired = photoRequired) }
            val stop = (routeRepository.getStop(customerId, LocalDate.now()) as? DataResult.Success)
                ?.data

            when (val result = workflowRepository.workflow(visitId)) {
                is DataResult.Success -> _state.update {
                    it.copy(
                        loading = false,
                        customer = stop?.customer ?: it.customer,
                        workflow = result.data,
                    )
                }

                is DataResult.Failure -> _state.update {
                    it.copy(loading = false, error = uiText(R.string.incall_load_failed))
                }
            }
        }
    }

    fun newPhotoTarget(): PhotoTarget = photoStore.newTarget().also {
        pendingPhoto = it
        _state.update { state -> state.copy(capturing = true, error = null) }
    }

    /** Same handling as the check-in photo: a retake replaces, and deletes, the last. */
    fun onPhotoTaken(saved: Boolean) {
        val target = pendingPhoto
        pendingPhoto = null
        if (!saved || target == null) {
            target?.let { photoStore.delete(it.path) }
            _state.update { it.copy(capturing = false) }
            return
        }
        viewModelScope.launch {
            if (photoStore.compress(target.path) <= 0L) {
                photoStore.delete(target.path)
                _state.update { it.copy(capturing = false, error = uiText(R.string.photo_save_failed)) }
                return@launch
            }
            _state.value.photoLocalPath?.let(photoStore::delete)
            _state.update { it.copy(capturing = false, photoLocalPath = target.path) }
        }
    }

    fun removePhoto() {
        _state.value.photoLocalPath?.let(photoStore::delete)
        _state.update { it.copy(photoLocalPath = null) }
    }

    fun checkOut() {
        if (!_state.value.canCheckOut) return
        val photo = _state.value.photoLocalPath

        _state.update { it.copy(submitting = true, error = null) }
        viewModelScope.launch {
            // A queued check-out still closes the visit for the rep; the route
            // screen's pending banner is what tells them it has yet to be sent.
            when (checkInRepository.checkOut(visitId, photo)) {
                is DataResult.Success -> _state.update {
                    it.copy(submitting = false, checkedOut = true, photoLocalPath = null)
                }

                is DataResult.Failure -> _state.update {
                    it.copy(submitting = false, error = uiText(R.string.incall_checkout_failed))
                }
            }
        }
    }
}
