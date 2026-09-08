package com.tinhcd.myesalessfa.feature.incall.steps

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tinhcd.myesalessfa.core.audio.VoiceRecorder
import com.tinhcd.myesalessfa.core.location.LocationProvider
import com.tinhcd.myesalessfa.core.photo.PhotoStore
import com.tinhcd.myesalessfa.core.photo.PhotoTarget
import com.tinhcd.myesalessfa.domain.DataResult
import com.tinhcd.myesalessfa.domain.model.DraftFeedback
import com.tinhcd.myesalessfa.domain.model.FeedbackPhoto
import com.tinhcd.myesalessfa.domain.model.FeedbackRecording
import com.tinhcd.myesalessfa.domain.model.ReasonCode
import com.tinhcd.myesalessfa.domain.model.ReasonKind
import com.tinhcd.myesalessfa.domain.model.StepConfig
import com.tinhcd.myesalessfa.domain.repository.ConfigRepository
import com.tinhcd.myesalessfa.domain.repository.FeedbackRepository
import com.tinhcd.myesalessfa.domain.repository.WorkflowRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class FeedbackUiState(
    val loading: Boolean = true,
    val title: String = "",
    val topics: List<ReasonCode> = emptyList(),
    val draft: DraftFeedback = DraftFeedback(visitId = ""),
    val recording: Boolean = false,
    /** Seconds captured so far while recording, for a live counter. */
    val recordingSeconds: Int = 0,
    /** Which clip is playing, by local path. Null when nothing is. */
    val playingPath: String? = null,
    val capturing: Boolean = false,
    val submitting: Boolean = false,
    val error: String? = null,
    val finished: Boolean = false,
) {
    val canSubmit: Boolean
        get() = !loading && !submitting && !recording && !capturing && draft.canSubmit
}

/**
 * Backs the `feedback` step.
 *
 * Split out of [NoteStepViewModel], which it used to share. That screen records free
 * text and nothing else, and feedback has outgrown it three times over: a coded topic
 * so head office can route what the customer said, photographs of whatever is being
 * complained about, and voice, because a rep in a loud shop cannot type Vietnamese
 * quickly.
 *
 * Everything variable is read from the step's own row — the heading, the minimum note
 * length, how many photos, whether audio is offered and how long it may run.
 */
@HiltViewModel
class FeedbackViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val workflowRepository: WorkflowRepository,
    private val configRepository: ConfigRepository,
    private val feedbackRepository: FeedbackRepository,
    private val recorder: VoiceRecorder,
    private val photoStore: PhotoStore,
    private val locationProvider: LocationProvider,
) : ViewModel() {

    private val visitId: String = checkNotNull(savedStateHandle["visitId"])
    private val formId: String = checkNotNull(savedStateHandle["formId"])

    private val _state = MutableStateFlow(FeedbackUiState())
    val state: StateFlow<FeedbackUiState> = _state.asStateFlow()

    private var ticker: Job? = null

    /** The file the camera is currently writing into, if any. */
    private var pendingPhoto: PhotoTarget? = null

    /** The file the recorder is currently writing into, if any. */
    private var pendingClip: String? = null

    init {
        viewModelScope.launch {
            val definition = (workflowRepository.step(formId) as? DataResult.Success)?.data
            val topics = configRepository.reasons(ReasonKind.FEEDBACK_TOPIC)

            _state.update { state ->
                state.copy(
                    loading = false,
                    title = definition?.let { configRepository.translate(it.titleKey) }.orEmpty(),
                    topics = topics,
                    draft = DraftFeedback(
                        visitId = visitId,
                        // A required step saved with an empty note records nothing yet
                        // unblocks check-out, so it always needs at least one character
                        // even where head office configured no minimum. Same rule the
                        // note step applies.
                        noteMinLength = (definition?.configInt(StepConfig.NOTE_MIN_LENGTH) ?: 0)
                            .let { if (definition?.isRequired == true) maxOf(1, it) else it },
                        // No floor by default: plenty of feedback is about a price or a
                        // delivery, with nothing to point a camera at.
                        photoMin = definition?.configInt(StepConfig.PHOTO_MIN, default = 0) ?: 0,
                        photoMax = definition?.configInt(StepConfig.PHOTO_MAX, default = 5) ?: 5,
                        audioMaxSeconds = definition
                            ?.configInt(StepConfig.AUDIO_MAX_SECONDS, default = 300) ?: 300,
                        audioTotalSeconds = definition
                            ?.configInt(StepConfig.AUDIO_TOTAL_SECONDS, default = 900) ?: 900,
                        allowAudio = definition?.configBoolean(StepConfig.ALLOW_AUDIO) ?: false,
                    ),
                )
            }
        }
    }

    fun onNoteChange(value: String) = _state.update {
        it.copy(draft = it.draft.copy(note = value), error = null)
    }

    /** Tapping the chosen topic again clears it, since the topic is optional. */
    fun onTopicChange(topicId: String) = _state.update {
        val next = if (it.draft.topicId == topicId) null else topicId
        it.copy(draft = it.draft.copy(topicId = next), error = null)
    }

    // -------------------------------------------------------------------------
    // Photos
    // -------------------------------------------------------------------------

    /**
     * Hands the camera somewhere to write. Called immediately before launching it, so
     * the file exists by the time the camera app resolves the uri.
     */
    fun newPhotoTarget(): PhotoTarget = photoStore.newTarget().also {
        pendingPhoto = it
        _state.update { state -> state.copy(capturing = true, error = null) }
    }

    /**
     * Records the photo the camera just wrote. Compressed here rather than at upload
     * time so the rep waits for it once, standing in the shop, instead of the upload
     * stalling on it later.
     */
    fun onPhotoTaken(saved: Boolean) {
        val target = pendingPhoto
        pendingPhoto = null

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
                    draft = it.draft.withPhoto(
                        FeedbackPhoto(
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
    fun onRemovePhoto(localPath: String) {
        photoStore.delete(localPath)
        _state.update { it.copy(draft = it.draft.withoutPhoto(localPath), error = null) }
    }

    // -------------------------------------------------------------------------
    // Recordings
    // -------------------------------------------------------------------------

    fun startRecording() {
        val current = _state.value
        if (current.recording || !current.draft.canRecord) return

        stopPlayback()

        // The clip stops at whichever comes first: the per-clip cap, or what is left
        // of the step's total. A rep who keeps recording after the cap starts a new
        // clip rather than losing the rest of the sentence.
        val limit = current.draft.nextClipSeconds

        val path = runCatching { recorder.start(limit) }.getOrElse { error ->
            _state.update {
                it.copy(
                    error = "Không bật được micro" + (error.message?.let { m -> ": $m" } ?: ""),
                )
            }
            return
        }

        pendingClip = path
        _state.update { it.copy(recording = true, recordingSeconds = 0, error = null) }

        // The recorder enforces the ceiling itself, so this only has to keep the
        // counter honest and notice when the ceiling was reached.
        ticker = viewModelScope.launch {
            while (recorder.isRecording) {
                _state.update { it.copy(recordingSeconds = recorder.elapsedSeconds()) }
                delay(500)
            }
            if (_state.value.recording) stopRecording()
        }
    }

    fun stopRecording() {
        if (!_state.value.recording) return
        ticker?.cancel()
        ticker = null

        val path = pendingClip
        pendingClip = null
        val seconds = recorder.stop()

        _state.update {
            it.copy(
                recording = false,
                recordingSeconds = 0,
                // Under a second is nothing to listen to and the recorder has already
                // thrown the file away, so nothing must be added pointing at it.
                draft = if (seconds < 1 || path == null) {
                    it.draft
                } else {
                    it.draft.withRecording(
                        FeedbackRecording(
                            localPath = path,
                            seconds = seconds,
                            recordedAtEpochMs = System.currentTimeMillis(),
                            sizeBytes = recorder.sizeOf(path),
                        ),
                    )
                },
                error = if (seconds < 1) "Bản ghi quá ngắn" else it.error,
            )
        }
    }

    fun playRecording(localPath: String) {
        _state.update { it.copy(playingPath = localPath) }
        recorder.play(localPath) {
            _state.update { it.copy(playingPath = null) }
        }
    }

    fun stopPlayback() {
        recorder.stopPlayback()
        _state.update { it.copy(playingPath = null) }
    }

    fun onRemoveRecording(localPath: String) {
        if (_state.value.playingPath == localPath) stopPlayback()
        recorder.delete(localPath)
        _state.update { it.copy(draft = it.draft.withoutRecording(localPath), error = null) }
    }

    // -------------------------------------------------------------------------

    fun submit() {
        val current = _state.value
        if (!current.canSubmit) return
        _state.update { it.copy(submitting = true, error = null) }

        viewModelScope.launch {
            recorder.stopPlayback()
            when (feedbackRepository.submit(current.draft)) {
                // QUEUED is not surfaced: the feedback is recorded either way, and the
                // route screen already shows how much is waiting to reach the server.
                is DataResult.Success -> _state.update {
                    it.copy(submitting = false, playingPath = null, finished = true)
                }

                is DataResult.Failure -> _state.update {
                    it.copy(submitting = false, error = "Không lưu được phản hồi")
                }
            }
        }
    }

    override fun onCleared() {
        // Leaving the screen mid-recording must not leave the microphone held open for
        // the rest of the visit.
        ticker?.cancel()
        if (recorder.isRecording) recorder.stop()
        recorder.stopPlayback()

        // Nothing has been uploaded yet, so the files go with the draft.
        val draft = _state.value.draft
        if (!_state.value.finished) {
            draft.photos.forEach { photoStore.delete(it.localPath) }
            draft.recordings.forEach { recorder.delete(it.localPath) }
        }
    }
}
