package com.tinhcd.myesalessfa.feature.worknote

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tinhcd.myesalessfa.domain.AppError
import com.tinhcd.myesalessfa.domain.DataResult
import com.tinhcd.myesalessfa.domain.model.WorkNote
import com.tinhcd.myesalessfa.domain.model.WorkNoteCompletion
import com.tinhcd.myesalessfa.domain.model.WorkNoteDraft
import com.tinhcd.myesalessfa.domain.model.WorkNoteStatus
import com.tinhcd.myesalessfa.domain.repository.WorkNoteRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

/** Which notes the list is showing. Null in the domain call means everything. */
enum class WorkNoteFilter(val label: String, val status: WorkNoteStatus?) {
    OPEN("Đang mở", WorkNoteStatus.OPEN),
    DONE("Đã xong", WorkNoteStatus.DONE),
    ALL("Tất cả", null),
}

data class WorkNoteUiState(
    val loading: Boolean = true,
    val filter: WorkNoteFilter = WorkNoteFilter.OPEN,
    val notes: List<WorkNote> = emptyList(),
    /** Non-null while the add sheet is open. */
    val draft: WorkNoteDraft? = null,
    /** Non-null while the rep is closing a note. */
    val completing: WorkNoteCompletion? = null,
    /** The note a delete is being confirmed for. */
    val deleting: WorkNote? = null,
    val busy: Boolean = false,
    val error: String? = null,
) {
    val today: LocalDate get() = LocalDate.now()

    val overdueCount: Int get() = notes.count { it.isOverdue(today) }
}

@HiltViewModel
class WorkNoteViewModel @Inject constructor(
    private val repository: WorkNoteRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(WorkNoteUiState())
    val state: StateFlow<WorkNoteUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            when (val result = repository.notes(_state.value.filter.status)) {
                is DataResult.Success ->
                    _state.update { it.copy(loading = false, notes = result.data) }

                is DataResult.Failure ->
                    _state.update { it.copy(loading = false, error = "Không tải được ghi chú") }
            }
        }
    }

    fun onFilterChanged(filter: WorkNoteFilter) {
        _state.update { it.copy(filter = filter) }
        load()
    }

    // --- Adding ---------------------------------------------------------------

    fun startAdding() = _state.update { it.copy(draft = WorkNoteDraft(), error = null) }

    fun cancelAdding() = _state.update { it.copy(draft = null) }

    fun onTitle(value: String) = editDraft { it.copy(title = value) }

    fun onBody(value: String) = editDraft { it.copy(body = value) }

    fun onDueOn(value: LocalDate?) = editDraft { it.copy(dueOn = value) }

    fun saveDraft() {
        val draft = _state.value.draft ?: return
        if (_state.value.busy || !draft.canSubmit) return

        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            when (val result = repository.add(draft)) {
                is DataResult.Success -> {
                    _state.update { it.copy(busy = false, draft = null) }
                    load()
                }

                is DataResult.Failure ->
                    _state.update { it.copy(busy = false, error = result.error.noteMessage()) }
            }
        }
    }

    // --- Closing --------------------------------------------------------------

    fun startCompleting(note: WorkNote) =
        _state.update { it.copy(completing = WorkNoteCompletion(note.noteId), error = null) }

    fun cancelCompleting() = _state.update { it.copy(completing = null) }

    fun onResult(value: String) =
        _state.update { it.copy(completing = it.completing?.copy(result = value)) }

    fun confirmCompleting() {
        val completion = _state.value.completing ?: return
        if (_state.value.busy || !completion.canSubmit) return

        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            when (val result = repository.complete(completion.noteId, completion.result)) {
                is DataResult.Success -> {
                    _state.update { it.copy(busy = false, completing = null) }
                    load()
                }

                is DataResult.Failure ->
                    _state.update { it.copy(busy = false, error = result.error.noteMessage()) }
            }
        }
    }

    // --- Deleting -------------------------------------------------------------

    fun startDeleting(note: WorkNote) = _state.update { it.copy(deleting = note, error = null) }

    fun cancelDeleting() = _state.update { it.copy(deleting = null) }

    fun confirmDeleting() {
        val note = _state.value.deleting ?: return
        if (_state.value.busy) return

        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            when (val result = repository.delete(note.noteId)) {
                is DataResult.Success -> {
                    _state.update { it.copy(busy = false, deleting = null) }
                    load()
                }

                is DataResult.Failure ->
                    _state.update { it.copy(busy = false, error = result.error.noteMessage()) }
            }
        }
    }

    private fun editDraft(change: (WorkNoteDraft) -> WorkNoteDraft) =
        _state.update { it.copy(draft = it.draft?.let(change), error = null) }
}

private fun AppError.noteMessage(): String = when (this) {
    is AppError.Network -> "Không có kết nối mạng"
    is AppError.Auth -> "Phiên đăng nhập đã hết hạn, đăng nhập lại"
    is AppError.Server -> message.orFallback()
    is AppError.Rule -> message.orFallback()
    is AppError.Unknown -> message.orFallback()
}

private fun String?.orFallback(): String =
    this?.takeIf { it.isNotBlank() }?.replaceFirstChar { it.uppercase() }
        ?: "Chưa lưu được, thử lại"
