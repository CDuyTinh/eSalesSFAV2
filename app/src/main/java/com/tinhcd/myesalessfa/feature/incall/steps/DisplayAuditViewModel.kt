package com.tinhcd.myesalessfa.feature.incall.steps

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tinhcd.myesalessfa.core.location.LocationProvider
import com.tinhcd.myesalessfa.core.photo.PhotoStore
import com.tinhcd.myesalessfa.core.photo.PhotoTarget
import com.tinhcd.myesalessfa.domain.DataResult
import com.tinhcd.myesalessfa.domain.model.AuditPhoto
import com.tinhcd.myesalessfa.domain.model.DisplayProgram
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

/**
 * Two pages, because the step asks two different questions.
 *
 * [PROGRAMS] is "which commitment am I checking" — the legacy's programme sheet.
 * [AUDIT] is "how does this one look", one programme at a time.
 *
 * An outlet in no programme never sees the first page: there is nothing to pick,
 * and the step is the plain photo record it has always been.
 */
enum class DisplayAuditPage { PROGRAMS, AUDIT }

data class DisplayAuditUiState(
    val loading: Boolean = true,
    val title: String = "",
    val page: DisplayAuditPage = DisplayAuditPage.PROGRAMS,
    val programs: List<DisplayProgram> = emptyList(),
    val audit: DraftDisplayAudit = DraftDisplayAudit(visitId = "", customerId = ""),
    val capturing: Boolean = false,
    val submitting: Boolean = false,
    val error: String? = null,
    val finished: Boolean = false,
) {
    /** Programmes already scored on this visit — what the list page counts down. */
    val scoredCount: Int get() = programs.count { it.isScored }

    /** True once nothing is left to score, which is when the step goes green. */
    val allScored: Boolean get() = programs.isNotEmpty() && scoredCount == programs.size
}

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

    /** From the step's config, and reused for every programme's draft. */
    private var photoMin = 1
    private var photoMax = 6

    init {
        viewModelScope.launch {
            val step = (workflowRepository.step(FORM_ID) as? DataResult.Success)?.data
            val title = step?.let { configRepository.translate(it.titleKey) }.orEmpty()

            // Defaults to one, not zero: this step exists to produce a picture, and
            // an unconfigured market still means at least one. Six is the legacy's
            // own DISPLAY_IMAGE default.
            photoMin = step?.configInt(StepConfig.PHOTO_MIN, default = 1) ?: 1
            photoMax = step?.configInt(StepConfig.PHOTO_MAX, default = 6) ?: 6

            val programs = when (val r = displayAuditRepository.programs(customerId, visitId)) {
                is DataResult.Success -> r.data
                // A listing that will not load must not block the step. The rep
                // still records the shelf; the programmes simply go unscored.
                is DataResult.Failure -> emptyList()
            }

            _state.update {
                it.copy(
                    loading = false,
                    title = title,
                    programs = programs,
                    page = if (programs.isEmpty()) {
                        DisplayAuditPage.AUDIT
                    } else {
                        DisplayAuditPage.PROGRAMS
                    },
                    audit = newDraft(program = null),
                )
            }
        }
    }

    private fun newDraft(program: DisplayProgram?) = DraftDisplayAudit(
        visitId = visitId,
        customerId = customerId,
        photoMin = photoMin,
        photoMax = photoMax,
        program = program,
        // Prefilled when the programme was already scored on this visit, so
        // rescoring starts from what was recorded rather than from blank. The
        // photos deliberately do not come back: they live on the server now, and
        // rescoring replaces them with what the rep is looking at.
        countedFaces = program?.countedFaces,
        achieved = program?.achieved,
    )

    /** Opens one programme for scoring. Anything half-shot on another is discarded. */
    fun onOpenProgram(program: DisplayProgram) {
        discardPhotos()
        _state.update {
            it.copy(page = DisplayAuditPage.AUDIT, audit = newDraft(program), error = null)
        }
    }

    /** Backs out of a programme without scoring it, dropping its photos. */
    fun onLeaveProgram() {
        if (_state.value.programs.isEmpty()) return
        discardPhotos()
        _state.update {
            it.copy(page = DisplayAuditPage.PROGRAMS, audit = newDraft(null), error = null)
        }
    }

    /** Nothing has been uploaded yet, so the files go with the draft. */
    private fun discardPhotos() {
        _state.value.audit.photos.forEach { photoStore.delete(it.localPath) }
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
                    it.copy(capturing = false, error = "Không lưu được ảnh, thử lại")
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

    /** FaceRemark. Floored at zero: a shelf cannot hold a negative number of facings. */
    fun onCountedFacesChange(value: Int) = _state.update {
        it.copy(audit = it.audit.copy(countedFaces = value.coerceAtLeast(0)), error = null)
    }

    /**
     * Evaluate — the rep's own verdict, kept separate from the count on purpose.
     * A display can miss the facing target and still be built to specification, or
     * hit it with the wrong products, which is why the legacy asks for both.
     */
    fun onAchievedChange(value: Boolean) =
        _state.update { it.copy(audit = it.audit.copy(achieved = value), error = null) }

    fun submit() {
        val current = _state.value
        if (current.submitting || !current.audit.canSubmit) return

        _state.update { it.copy(submitting = true, error = null) }
        viewModelScope.launch {
            when (displayAuditRepository.submit(current.audit)) {
                is DataResult.Success -> onSubmitted(current)

                is DataResult.Failure -> _state.update {
                    it.copy(submitting = false, error = "Không lưu được phiếu chấm trưng bày")
                }
            }
        }
    }

    /**
     * With no programmes the step is over the moment the photos land. With them,
     * the rep is returned to the list to score whatever is left — and the step only
     * finishes when nothing is, which is the same rule `submit_display_audit`
     * applies before it marks the step done.
     */
    private suspend fun onSubmitted(before: DisplayAuditUiState) {
        if (before.programs.isEmpty()) {
            _state.update { it.copy(submitting = false, finished = true) }
            return
        }

        val refreshed =
            when (val r = displayAuditRepository.programs(customerId, visitId)) {
                is DataResult.Success -> r.data
                // The write went through; a failed re-read is not worth losing it
                // over, so the just-scored programme is marked from what was sent.
                is DataResult.Failure -> before.programs.map {
                    if (it.programId == before.audit.program?.programId) {
                        it.copy(
                            countedFaces = before.audit.countedFaces,
                            achieved = before.audit.achieved,
                            photoCount = before.audit.photoCount,
                        )
                    } else {
                        it
                    }
                }
            }

        _state.update {
            it.copy(
                submitting = false,
                programs = refreshed,
                page = DisplayAuditPage.PROGRAMS,
                audit = newDraft(null),
                finished = refreshed.isNotEmpty() && refreshed.all { p -> p.isScored },
            )
        }
    }

    private companion object {
        const val FORM_ID = "display_remark"
    }
}
