package com.tinhcd.myesalessfa.feature.incall.steps

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tinhcd.myesalessfa.core.location.LocationProvider
import com.tinhcd.myesalessfa.core.photo.PhotoStore
import com.tinhcd.myesalessfa.core.photo.PhotoTarget
import com.tinhcd.myesalessfa.domain.DataResult
import com.tinhcd.myesalessfa.domain.model.AuditPhoto
import com.tinhcd.myesalessfa.domain.model.DraftDisplayAudit
import com.tinhcd.myesalessfa.domain.model.StepConfig
import com.tinhcd.myesalessfa.domain.repository.ConfigRepository
import com.tinhcd.myesalessfa.domain.repository.DisplayAuditRepository
import com.tinhcd.myesalessfa.domain.repository.WorkflowRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DisplayAuditUiState(
    val loading: Boolean = true,
    val title: String = "",
    val audit: DraftDisplayAudit = DraftDisplayAudit(visitId = "", customerId = ""),
    val capturing: Boolean = false,
    val submitting: Boolean = false,
    val error: String? = null,
    val finished: Boolean = false,
)

/**
 * Backs the `display_remark` step.
 *
 * How many photos are enough comes from the step's own `photo_min`, the same key
 * `submit_display_audit` reads. The client enforces it so the rep is stopped while
 * still in front of the shelf, rather than finding out when the upload fails from
 * somewhere else entirely.
 */
@HiltViewModel
class DisplayAuditViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val workflowRepository: WorkflowRepository,
    private val configRepository: ConfigRepository,
    private val displayAuditRepository: DisplayAuditRepository,
    private val photoStore: PhotoStore,
    private val locationProvider: LocationProvider,
) : ViewModel() {

    private val visitId: String = checkNotNull(savedStateHandle["visitId"])
    private val customerId: String = checkNotNull(savedStateHandle["customerId"])

    private val _state = MutableStateFlow(DisplayAuditUiState())
    val state: StateFlow<DisplayAuditUiState> = _state.asStateFlow()

    /** The file the camera is currently writing into, if any. */
    private var pending: PhotoTarget? = null

    init {
        viewModelScope.launch {
            val step = (workflowRepository.step(FORM_ID) as? DataResult.Success)?.data
            val title = step?.let { configRepository.translate(it.titleKey) }.orEmpty()

            _state.update {
                it.copy(
                    loading = false,
                    title = title,
                    audit = DraftDisplayAudit(
                        visitId = visitId,
                        customerId = customerId,
                        // Defaults to one, not zero: this step exists to produce a
                        // picture, and an unconfigured market still means at least one.
                        photoMin = step?.configInt(StepConfig.PHOTO_MIN, default = 1) ?: 1,
                    ),
                )
            }
        }
    }

    /**
     * Hands the camera somewhere to write. Called immediately before launching it, so
     * the file exists by the time the camera app resolves the uri.
     */
    fun newPhotoTarget(): PhotoTarget = photoStore.newTarget().also {
        pending = it
        _state.update { state -> state.copy(capturing = true, error = null) }
    }

    /**
     * Records the photo the camera just wrote.
     *
     * Compression happens here rather than at upload time so the rep waits for it
     * once, while looking at the shelf, instead of the upload stalling on it later.
     * Position is stamped too — a display photo with no location is hard to dispute
     * and hard to trust.
     */
    fun onPhotoTaken(saved: Boolean) {
        val target = pending
        pending = null

        if (!saved || target == null) {
            // Cancelled. The camera may still have created an empty file.
            target?.let { photoStore.delete(it.path) }
            _state.update { it.copy(capturing = false) }
            return
        }

        viewModelScope.launch {
            val size = photoStore.compress(target.path)
            if (size <= 0L) {
                photoStore.delete(target.path)
                _state.update {
                    it.copy(capturing = false, error = "Khong luu duoc anh, thu lai")
                }
                return@launch
            }

            val point = runCatching { locationProvider.currentLocation() }.getOrNull()

            _state.update {
                it.copy(
                    capturing = false,
                    audit = it.audit.withPhoto(
                        AuditPhoto(
                            localPath = target.path,
                            takenAtEpochMs = System.currentTimeMillis(),
                            lat = point?.lat,
                            lng = point?.lng,
                            sizeBytes = size,
                        ),
                    ),
                )
            }
        }
    }

    /** Removes a rejected shot and the file behind it — nothing has been uploaded yet. */
    fun onRemovePhoto(localPath: String) {
        photoStore.delete(localPath)
        _state.update {
            it.copy(audit = it.audit.withoutPhoto(localPath), error = null)
        }
    }

    fun onNoteChange(value: String) =
        _state.update { it.copy(audit = it.audit.copy(note = value), error = null) }

    fun submit() {
        val current = _state.value
        if (current.submitting || !current.audit.canSubmit) return

        _state.update { it.copy(submitting = true, error = null) }
        viewModelScope.launch {
            when (displayAuditRepository.submit(current.audit)) {
                is DataResult.Success -> _state.update {
                    it.copy(submitting = false, finished = true)
                }

                is DataResult.Failure -> _state.update {
                    it.copy(submitting = false, error = "Khong luu duoc phieu cham trung bay")
                }
            }
        }
    }

    private companion object {
        const val FORM_ID = "display_remark"
    }
}
