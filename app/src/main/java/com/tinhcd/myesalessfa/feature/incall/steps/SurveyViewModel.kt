package com.tinhcd.myesalessfa.feature.incall.steps

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tinhcd.myesalessfa.domain.DataResult
import com.tinhcd.myesalessfa.domain.model.DraftSurvey
import com.tinhcd.myesalessfa.domain.repository.SurveyRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SurveyUiState(
    val loading: Boolean = true,
    val survey: DraftSurvey? = null,
    val submitting: Boolean = false,
    val error: String? = null,
    val finished: Boolean = false,
)

/**
 * Backs every questionnaire step — `posm_status`, `market_info`, and whatever is added
 * next.
 *
 * It has no idea which questionnaire it is showing. The step's form id selects the
 * definition, and the definition supplies the groups, questions, answer types, options
 * and scores. Adding a third questionnaire step is a row on the server and a form id
 * in `sales_step`; no code lands here.
 */
@HiltViewModel
class SurveyViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val surveyRepository: SurveyRepository,
) : ViewModel() {

    private val visitId: String = checkNotNull(savedStateHandle["visitId"])
    private val customerId: String = checkNotNull(savedStateHandle["customerId"])
    private val formId: String = checkNotNull(savedStateHandle["formId"])

    private val _state = MutableStateFlow(SurveyUiState())
    val state: StateFlow<SurveyUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            when (val result = surveyRepository.definition(formId)) {
                is DataResult.Success -> {
                    val definition = result.data
                    _state.update {
                        it.copy(
                            loading = false,
                            survey = definition?.let { found ->
                                DraftSurvey(
                                    visitId = visitId,
                                    customerId = customerId,
                                    definition = found,
                                )
                            },
                            // Reported rather than shown as an empty form: a step with
                            // no questionnaire behind it cannot be completed, and the
                            // rep needs to know that rather than wonder.
                            error = if (definition == null) {
                                "Chưa cấu hình bộ câu hỏi cho bước này"
                            } else {
                                null
                            },
                        )
                    }
                }

                is DataResult.Failure -> _state.update {
                    it.copy(loading = false, error = "Không tải được bộ câu hỏi")
                }
            }
        }
    }

    fun onYesNo(questionId: String, value: Boolean) = update { it.withYesNo(questionId, value) }

    fun onNumber(questionId: String, raw: String) = update {
        // Blank clears the answer, which is what leaves a required question unanswered.
        it.withNumber(questionId, raw.trim().ifBlank { null }?.toDoubleOrNull())
    }

    fun onText(questionId: String, value: String) = update { it.withText(questionId, value) }

    fun onSingleOption(questionId: String, optionId: String) =
        update { it.withOption(questionId, optionId) }

    fun onToggleOption(questionId: String, optionId: String) =
        update { it.withToggledOption(questionId, optionId) }

    fun onNoteChange(value: String) = update { it.copy(note = value) }

    fun submit() {
        val current = _state.value
        val survey = current.survey ?: return
        if (current.submitting || !survey.canSubmit) return

        _state.update { it.copy(submitting = true, error = null) }
        viewModelScope.launch {
            when (surveyRepository.submit(survey)) {
                is DataResult.Success -> _state.update {
                    it.copy(submitting = false, finished = true)
                }

                is DataResult.Failure -> _state.update {
                    it.copy(submitting = false, error = "Không lưu được phiếu khảo sát")
                }
            }
        }
    }

    private fun update(transform: (DraftSurvey) -> DraftSurvey) {
        _state.update { state ->
            val survey = state.survey ?: return@update state
            state.copy(survey = transform(survey), error = null)
        }
    }
}
