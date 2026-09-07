package com.tinhcd.myesalessfa.feature.incall.steps

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tinhcd.myesalessfa.core.location.LocationProvider
import com.tinhcd.myesalessfa.core.photo.PhotoStore
import com.tinhcd.myesalessfa.core.photo.PhotoTarget
import com.tinhcd.myesalessfa.domain.DataResult
import com.tinhcd.myesalessfa.domain.model.CompetitorCriterion
import com.tinhcd.myesalessfa.domain.model.CompetitorPairing
import com.tinhcd.myesalessfa.domain.model.CompetitorPhoto
import com.tinhcd.myesalessfa.domain.model.DraftCompetitorSurvey
import com.tinhcd.myesalessfa.domain.model.MarketInfoSurvey
import com.tinhcd.myesalessfa.domain.model.StepConfig
import com.tinhcd.myesalessfa.domain.model.SupportedSteps
import com.tinhcd.myesalessfa.domain.repository.ConfigRepository
import com.tinhcd.myesalessfa.domain.repository.MarketInfoRepository
import com.tinhcd.myesalessfa.domain.repository.WorkflowRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The list of surveys, or the grid of one competitor survey. */
enum class MarketInfoPage { LIST, COMPETITOR }

data class MarketInfoUiState(
    val loading: Boolean = true,
    val title: String = "",
    val page: MarketInfoPage = MarketInfoPage.LIST,
    val surveys: List<MarketInfoSurvey> = emptyList(),
    val draft: DraftCompetitorSurvey? = null,
    val capturing: Boolean = false,
    val submitting: Boolean = false,
    val error: String? = null,
) {
    val doneCount: Int get() = surveys.count { it.isCompleted }

    /** True once nothing is outstanding, which is when the step goes green. */
    val allDone: Boolean get() = surveys.isNotEmpty() && doneCount == surveys.size

    /** No survey at all — a branch running none today, which is not an error. */
    val nothingToDo: Boolean get() = !loading && surveys.isEmpty()
}

/**
 * Backs the `market_info` step.
 *
 * The step used to be one questionnaire. It is a list: an outlet owes every
 * survey live for its branch today, and they come in two kinds. The questionnaire
 * kind opens the shared survey screen, which is why this view model never touches
 * it — it only routes there. The competitor kind is a grid it owns outright.
 */
@HiltViewModel
class MarketInfoViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val workflowRepository: WorkflowRepository,
    private val configRepository: ConfigRepository,
    private val marketInfoRepository: MarketInfoRepository,
    private val photoStore: PhotoStore,
    private val locationProvider: LocationProvider,
) : ViewModel() {

    private val visitId: String = checkNotNull(savedStateHandle["visitId"])
    private val customerId: String = checkNotNull(savedStateHandle["customerId"])

    private val _state = MutableStateFlow(MarketInfoUiState())
    val state: StateFlow<MarketInfoUiState> = _state.asStateFlow()

    /** The file the camera is currently writing into, if any. */
    private var pending: PhotoTarget? = null

    /** Which cell that photo belongs to; the camera comes back without context. */
    private var pendingCell: Pair<CompetitorPairing, CompetitorCriterion>? = null

    private var photoMax = 4

    init {
        viewModelScope.launch {
            val step = (workflowRepository.step(SupportedSteps.MARKET_INFO) as? DataResult.Success)
                ?.data
            val title = step?.let { configRepository.translate(it.titleKey) }.orEmpty()

            // No floor: a price written down is a report on its own, and a shop
            // that will not let a rep photograph a rival's shelf is common enough
            // that requiring one would stop the survey being filed at all.
            photoMax = step?.configInt(StepConfig.PHOTO_MAX, default = 4) ?: 4

            _state.update { it.copy(title = title.ifBlank { "Thông tin thị trường" }) }
            reload()
        }
    }

    fun reload() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            when (val r = marketInfoRepository.surveys(customerId, visitId)) {
                is DataResult.Success -> _state.update {
                    it.copy(loading = false, surveys = r.data)
                }

                is DataResult.Failure -> _state.update {
                    it.copy(loading = false, error = "Không tải được danh sách khảo sát")
                }
            }
        }
    }

    /** Opens one competitor survey. Anything half-shot on another is discarded. */
    fun onOpenCompetitorSurvey(survey: MarketInfoSurvey) {
        discardPhotos()
        _state.update { it.copy(loading = true, error = null, draft = null) }

        viewModelScope.launch {
            when (val r = marketInfoRepository.competitorSurvey(survey.id, visitId)) {
                is DataResult.Success -> _state.update {
                    it.copy(
                        loading = false,
                        page = MarketInfoPage.COMPETITOR,
                        draft = r.data.copy(photoMax = photoMax),
                    )
                }

                is DataResult.Failure -> _state.update {
                    it.copy(loading = false, error = "Không mở được khảo sát đối thủ")
                }
            }
        }
    }

    /** Backs out of a grid without filing it, dropping the photos it gathered. */
    fun onLeaveSurvey() {
        discardPhotos()
        _state.update { it.copy(page = MarketInfoPage.LIST, draft = null, error = null) }
    }

    fun onContent(
        pairing: CompetitorPairing,
        criterion: CompetitorCriterion,
        content: String,
    ) = _state.update {
        it.copy(draft = it.draft?.withContent(pairing, criterion, content), error = null)
    }

    /**
     * Hands the camera somewhere to write, remembering which cell asked for it.
     * Called immediately before launching it, so the file exists by the time the
     * camera app resolves the uri.
     */
    fun newPhotoTarget(
        pairing: CompetitorPairing,
        criterion: CompetitorCriterion,
    ): PhotoTarget = photoStore.newTarget().also {
        pending = it
        pendingCell = pairing to criterion
        _state.update { state -> state.copy(capturing = true, error = null) }
    }

    /**
     * Records the photo the camera just wrote. Compressed here rather than at
     * upload time so the rep waits for it once, standing in front of the shelf,
     * instead of the upload stalling on it later.
     */
    fun onPhotoTaken(saved: Boolean) {
        val target = pending
        val cell = pendingCell
        pending = null
        pendingCell = null

        if (!saved || target == null || cell == null) {
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
                    draft = it.draft?.withPhoto(
                        cell.first,
                        cell.second,
                        CompetitorPhoto(
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

    /** Removes a rejected shot and the file behind it — nothing is uploaded yet. */
    fun onRemovePhoto(
        pairing: CompetitorPairing,
        criterion: CompetitorCriterion,
        localPath: String,
    ) {
        photoStore.delete(localPath)
        _state.update {
            it.copy(draft = it.draft?.withoutPhoto(pairing, criterion, localPath), error = null)
        }
    }

    fun onSubmit() {
        val draft = _state.value.draft ?: return
        if (!draft.canSubmit || _state.value.submitting) return

        _state.update { it.copy(submitting = true, error = null) }

        viewModelScope.launch {
            when (marketInfoRepository.submit(draft)) {
                is DataResult.Success -> {
                    // Back to the list rather than out of the step: the outlet may
                    // owe another survey, and the rep is the one who decides
                    // whether to do it now.
                    _state.update {
                        it.copy(submitting = false, page = MarketInfoPage.LIST, draft = null)
                    }
                    reload()
                }

                is DataResult.Failure -> _state.update {
                    it.copy(submitting = false, error = "Không gửi được khảo sát, thử lại")
                }
            }
        }
    }

    fun onDismissError() = _state.update { it.copy(error = null) }

    /** Nothing has been uploaded yet, so the files go with the draft. */
    private fun discardPhotos() {
        _state.value.draft?.entries?.values?.forEach { entry ->
            entry.photos.forEach { photoStore.delete(it.localPath) }
        }
    }

    override fun onCleared() {
        discardPhotos()
        super.onCleared()
    }
}
