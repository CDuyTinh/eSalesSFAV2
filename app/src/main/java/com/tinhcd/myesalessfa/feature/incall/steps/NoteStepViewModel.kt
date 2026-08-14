package com.tinhcd.myesalessfa.feature.incall.steps

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tinhcd.myesalessfa.domain.DataResult
import com.tinhcd.myesalessfa.domain.model.StepConfig
import com.tinhcd.myesalessfa.domain.repository.ConfigRepository
import com.tinhcd.myesalessfa.domain.repository.WorkflowRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class NoteStepUiState(
    val loading: Boolean = true,
    val title: String = "",
    val note: String = "",
    /** From the step's own config; 0 means the server set no floor. */
    val minLength: Int = 0,
    val isRequired: Boolean = false,
    val submitting: Boolean = false,
    val error: String? = null,
    val finished: Boolean = false,
) {
    /**
     * A mandatory step saved with an empty note records nothing, yet unblocks
     * check-out — so a required step always needs at least one character even
     * when head office configured no minimum.
     */
    val requiredLength: Int get() = if (isRequired) maxOf(1, minLength) else minLength

    val canSubmit: Boolean get() = !loading && note.trim().length >= requiredLength
}

/**
 * Backs the two steps this build implements: the outside check and the customer
 * feedback note. Both record free text against the visit, so one screen serves
 * both rather than duplicating a form.
 *
 * Everything variable about it — the heading, whether text is mandatory, how
 * much of it — is read from the step's own row in `sales_step`. The screen knows
 * it renders a note; it does not know which step it is or what that step is
 * called.
 */
@HiltViewModel
class NoteStepViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val workflowRepository: WorkflowRepository,
    private val configRepository: ConfigRepository,
) : ViewModel() {

    private val visitId: String = checkNotNull(savedStateHandle["visitId"])
    private val formId: String = checkNotNull(savedStateHandle["formId"])

    private val _state = MutableStateFlow(NoteStepUiState())
    val state: StateFlow<NoteStepUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val definition = (workflowRepository.step(formId) as? DataResult.Success)?.data
            // Cache miss on a step the rep somehow reached: show the form with no
            // extra rules rather than an error, since the write itself is keyed
            // on formId and will still land.
            val title = definition?.let { configRepository.translate(it.titleKey) }.orEmpty()
            _state.update { state ->
                state.copy(
                    loading = false,
                    title = title,
                    minLength = definition?.configInt(StepConfig.NOTE_MIN_LENGTH) ?: 0,
                    isRequired = definition?.isRequired ?: false,
                )
            }
        }
    }

    fun onNoteChange(value: String) = _state.update { it.copy(note = value, error = null) }

    fun submit() {
        val current = _state.value
        if (current.submitting || !current.canSubmit) return
        _state.update { it.copy(submitting = true, error = null) }

        viewModelScope.launch {
            val result = workflowRepository.completeStep(
                visitId = visitId,
                formId = formId,
                payload = mapOf("note" to current.note.trim()),
            )
            when (result) {
                // QUEUED is not worth telling the rep about here: the step is
                // recorded either way, and the route screen already shows how
                // much is still waiting to reach the server.
                is DataResult.Success -> _state.update {
                    it.copy(submitting = false, finished = true)
                }

                is DataResult.Failure -> _state.update {
                    it.copy(submitting = false, error = "Khong luu duoc buoc nay")
                }
            }
        }
    }
}
