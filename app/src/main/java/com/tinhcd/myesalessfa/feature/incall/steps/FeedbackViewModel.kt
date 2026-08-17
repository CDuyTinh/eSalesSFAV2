package com.tinhcd.myesalessfa.feature.incall.steps

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tinhcd.myesalessfa.core.audio.VoiceRecorder
import com.tinhcd.myesalessfa.domain.DataResult
import com.tinhcd.myesalessfa.domain.model.DraftFeedback
import com.tinhcd.myesalessfa.domain.model.ReasonCode
import com.tinhcd.myesalessfa.domain.model.ReasonKind
import com.tinhcd.myesalessfa.domain.model.StepConfig
import com.tinhcd.myesalessfa.domain.repository.ConfigRepository
import com.tinhcd.myesalessfa.domain.repository.FeedbackRepository
import com.tinhcd.myesalessfa.domain.repository.WorkflowRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
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
    val playing: Boolean = false,
    val submitting: Boolean = false,
    val error: String? = null,
    val finished: Boolean = false,
) {
    val canSubmit: Boolean get() = !loading && !submitting && !recording && draft.canSubmit
}

/**
 * Backs the `feedback` step.
 *
 * Split out of [NoteStepViewModel], which it used to share. That screen records free
 * text and nothing else, and feedback has outgrown it: a coded topic so head office
 * can route what the customer said, and a voice note, which `sales_step` has been
 * advertising through `allow_audio` since the workflow was first seeded without
 * anything honouring it.
 *
 * Everything variable is still read from the step's own row — the heading, the minimum
 * note length, whether audio is offered at all.
 */
@HiltViewModel
class FeedbackViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val workflowRepository: WorkflowRepository,
    private val configRepository: ConfigRepository,
    private val feedbackRepository: FeedbackRepository,
    private val recorder: VoiceRecorder,
) : ViewModel() {

    private val visitId: String = checkNotNull(savedStateHandle["visitId"])
    private val formId: String = checkNotNull(savedStateHandle["formId"])

    private val _state = MutableStateFlow(FeedbackUiState())
    val state: StateFlow<FeedbackUiState> = _state.asStateFlow()

    private var ticker: Job? = null

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

    fun startRecording() {
        val current = _state.value
        if (current.recording || !current.draft.allowAudio) return

        // Re-recording replaces: two files for one visit and only one column to point
        // at them would leave the older bytes stranded on the device for ever.
        current.draft.audioPath?.let { recorder.delete(it) }
        recorder.stopPlayback()

        val started = runCatching { recorder.start() }
        val path = started.getOrElse { error ->
            _state.update {
                it.copy(
                    error = "Không bật được micro" + (error.message?.let { m -> ": $m" } ?: ""),
                    draft = it.draft.copy(audioPath = null, audioSeconds = 0),
                )
            }
            return
        }

        _state.update {
            it.copy(
                recording = true,
                recordingSeconds = 0,
                playing = false,
                error = null,
                draft = it.draft.copy(audioPath = path, audioSeconds = 0),
            )
        }

        // The recorder enforces its own two-minute ceiling, so this only has to keep
        // the counter honest and notice when the ceiling was reached.
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

        val seconds = recorder.stop()
        _state.update {
            it.copy(
                recording = false,
                recordingSeconds = 0,
                // Under a second is nothing to listen to and the recorder has already
                // thrown the file away, so the draft must not keep pointing at it.
                draft = if (seconds < 1) {
                    it.draft.copy(audioPath = null, audioSeconds = 0)
                } else {
                    it.draft.copy(audioSeconds = seconds)
                },
                error = if (seconds < 1) "Bản ghi quá ngắn" else it.error,
            )
        }
    }

    fun playRecording() {
        val path = _state.value.draft.audioPath ?: return
        _state.update { it.copy(playing = true) }
        recorder.play(path) {
            _state.update { it.copy(playing = false) }
        }
    }

    fun stopPlayback() {
        recorder.stopPlayback()
        _state.update { it.copy(playing = false) }
    }

    fun deleteRecording() {
        val current = _state.value
        recorder.stopPlayback()
        current.draft.audioPath?.let { recorder.delete(it) }
        _state.update {
            it.copy(
                playing = false,
                draft = it.draft.copy(audioPath = null, audioSeconds = 0),
                error = null,
            )
        }
    }

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
                    it.copy(submitting = false, playing = false, finished = true)
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
    }
}
