package com.tinhcd.myesalessfa.feature.workplan

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tinhcd.myesalessfa.domain.AppError
import com.tinhcd.myesalessfa.domain.DataResult
import com.tinhcd.myesalessfa.domain.model.DraftWorkPlan
import com.tinhcd.myesalessfa.domain.model.WorkPlan
import com.tinhcd.myesalessfa.domain.model.WorkPlanBoard
import com.tinhcd.myesalessfa.domain.model.WorkPlanDetail
import com.tinhcd.myesalessfa.domain.model.WorkPlanObject
import com.tinhcd.myesalessfa.domain.model.WorkPlanObjectKind
import com.tinhcd.myesalessfa.domain.model.WorkPlanStatus
import com.tinhcd.myesalessfa.domain.model.WorkWithCategory
import com.tinhcd.myesalessfa.domain.repository.WorkPlanRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.YearMonth
import javax.inject.Inject

/** Which page of the feature is on screen. */
enum class WorkPlanPage { LIST, DETAIL, EDIT, PICK_OBJECT }

/** The two halves of the legacy's two menu entries, as one switch. */
enum class WorkPlanScope(val label: String) {
    ALL("Tất cả"),
    MINE("Tôi lập"),
    ASSIGNED("Được giao"),
}

data class WorkPlanUiState(
    val loading: Boolean = true,
    val page: WorkPlanPage = WorkPlanPage.LIST,
    val board: WorkPlanBoard = WorkPlanBoard(),
    val month: YearMonth = YearMonth.now(),
    val scope: WorkPlanScope = WorkPlanScope.ALL,
    val statusFilter: WorkPlanStatus? = null,
    /** Null means the month; a day narrows the list to plans covering it. */
    val selectedDay: LocalDate? = null,

    val detail: WorkPlanDetail? = null,
    val draft: DraftWorkPlan = DraftWorkPlan(),

    /** The customers the object picker is choosing from. */
    val pickable: List<WorkPlanObject> = emptyList(),
    val pickerQuery: String = "",
    val pickerLoading: Boolean = false,

    val working: Boolean = false,
    val error: String? = null,
    val message: String? = null,
) {
    val visible: List<WorkPlan>
        get() = board.items.filter { plan ->
            (statusFilter == null || plan.status == statusFilter) &&
                (selectedDay == null || covers(plan, selectedDay))
        }

    val visiblePickable: List<WorkPlanObject>
        get() = if (pickerQuery.isBlank()) {
            pickable
        } else {
            val needle = pickerQuery.trim().lowercase()
            pickable.filter {
                it.name.lowercase().contains(needle) ||
                    it.code?.lowercase()?.contains(needle) == true
            }
        }

    val canSave: Boolean get() = !working && draft.canSave

    /**
     * The dates arrive as UTC timestamps, so they are shifted to the device's
     * zone before being compared with a calendar cell. Slicing the first ten
     * characters was wrong by a day for anything scheduled in the evening.
     */
    private fun covers(plan: WorkPlan, day: LocalDate): Boolean {
        val from = localDay(plan.fromAt) ?: return false
        val to = localDay(plan.toAt) ?: return false
        return !day.isBefore(from) && !day.isAfter(to)
    }

    private fun localDay(iso: String): LocalDate? = runCatching {
        OffsetDateTime.parse(iso).atZoneSameInstant(ZoneId.systemDefault()).toLocalDate()
    }.getOrNull()
}

/**
 * Kế hoạch làm việc.
 *
 * The legacy splits this in two: MY_TASK reads what a manager assigned, and
 * WORKING_PLAN writes what the rep planned. They are the same row with a
 * different author, so here they are one screen with a scope switch — which
 * also means the calendar and the status counts cover both, as the rep's month
 * actually does.
 */
@HiltViewModel
class WorkPlanViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: WorkPlanRepository,
) : ViewModel() {

    // The two legacy menu entries land here with a different scope. An unknown
    // value opens on everything, which is the harmless reading.
    private val opening: WorkPlanScope = runCatching {
        WorkPlanScope.valueOf(savedStateHandle.get<String>("scope") ?: "ALL")
    }.getOrDefault(WorkPlanScope.ALL)

    private val _state = MutableStateFlow(WorkPlanUiState(scope = opening))
    val state: StateFlow<WorkPlanUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        val current = _state.value
        _state.update { it.copy(loading = true, error = null) }

        viewModelScope.launch {
            val month = current.month
            when (
                val result = repository.board(
                    fromDate = month.atDay(1).toString(),
                    toDate = month.atEndOfMonth().toString(),
                    assignedToMe = when (current.scope) {
                        WorkPlanScope.ALL -> null
                        WorkPlanScope.MINE -> false
                        WorkPlanScope.ASSIGNED -> true
                    },
                )
            ) {
                is DataResult.Success -> _state.update {
                    it.copy(loading = false, board = result.data)
                }

                is DataResult.Failure -> _state.update {
                    it.copy(loading = false, error = "Không tải được kế hoạch làm việc")
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Moving around the list
    // -------------------------------------------------------------------------

    fun onMonth(next: YearMonth) {
        _state.update { it.copy(month = next, selectedDay = null) }
        load()
    }

    fun onScope(next: WorkPlanScope) {
        if (_state.value.scope == next) return
        _state.update { it.copy(scope = next, selectedDay = null) }
        load()
    }

    /** Tapping the selected chip clears it, as the filter row's chips do. */
    fun onStatusFilter(status: WorkPlanStatus?) = _state.update {
        it.copy(statusFilter = if (it.statusFilter == status) null else status)
    }

    fun onDay(day: LocalDate?) = _state.update {
        it.copy(selectedDay = if (it.selectedDay == day) null else day)
    }

    fun dismissMessage() = _state.update { it.copy(message = null, error = null) }

    // -------------------------------------------------------------------------
    // One plan
    // -------------------------------------------------------------------------

    fun onOpen(plan: WorkPlan) {
        _state.update { it.copy(page = WorkPlanPage.DETAIL, working = true, error = null) }

        viewModelScope.launch {
            when (val result = repository.detail(plan.id)) {
                is DataResult.Success -> _state.update {
                    it.copy(working = false, detail = result.data)
                }

                is DataResult.Failure -> _state.update {
                    it.copy(
                        working = false,
                        page = WorkPlanPage.LIST,
                        error = "Không mở được kế hoạch",
                    )
                }
            }
        }
    }

    fun onBackToList() = _state.update {
        it.copy(page = WorkPlanPage.LIST, detail = null, error = null)
    }

    // -------------------------------------------------------------------------
    // Writing one
    // -------------------------------------------------------------------------

    /** A blank form, defaulted to the selected day or today, 08:00 to 17:00. */
    fun onNew() {
        val day = _state.value.selectedDay ?: LocalDate.now()
        _state.update {
            it.copy(
                page = WorkPlanPage.EDIT,
                error = null,
                draft = DraftWorkPlan(
                    fromAt = day.atTime(8, 0).atZone(ZoneId.systemDefault())
                        .toOffsetDateTime().toString(),
                    toAt = day.atTime(17, 0).atZone(ZoneId.systemDefault())
                        .toOffsetDateTime().toString(),
                ),
            )
        }
    }

    /** The form filled from a draft the rep already wrote. */
    fun onEdit() {
        val detail = _state.value.detail ?: return
        if (!detail.plan.canEdit) return

        val category = _state.value.board.categories
            .firstOrNull { it.id == detail.plan.categoryId }

        _state.update {
            it.copy(
                page = WorkPlanPage.EDIT,
                error = null,
                draft = DraftWorkPlan(
                    id = detail.plan.id,
                    category = category,
                    content = detail.plan.content,
                    note = detail.plan.note.orEmpty(),
                    place = detail.plan.place.orEmpty(),
                    fromAt = detail.plan.fromAt,
                    toAt = detail.plan.toAt,
                    objects = detail.objects,
                ),
            )
        }
    }

    fun onCancelEdit() = _state.update {
        it.copy(
            page = if (it.detail != null) WorkPlanPage.DETAIL else WorkPlanPage.LIST,
            draft = DraftWorkPlan(),
            error = null,
        )
    }

    fun onCategory(category: WorkWithCategory) = _state.update {
        it.copy(draft = it.draft.withCategory(category), error = null)
    }

    fun onContent(value: String) = _state.update { it.copy(draft = it.draft.copy(content = value)) }
    fun onNote(value: String) = _state.update { it.copy(draft = it.draft.copy(note = value)) }
    fun onPlace(value: String) = _state.update { it.copy(draft = it.draft.copy(place = value)) }

    fun onWindow(fromAt: String, toAt: String) = _state.update {
        it.copy(draft = it.draft.copy(fromAt = fromAt, toAt = toAt), error = null)
    }

    // -------------------------------------------------------------------------
    // Choosing who the plan is with
    // -------------------------------------------------------------------------

    /**
     * Only customers can be picked here.
     *
     * The catalogue also knows plans with another rep or with the distributor;
     * this app has no directory of either, so those types are shown but not
     * offered a picker rather than pretending to one.
     */
    fun onOpenPicker() {
        if (_state.value.draft.objectKind != WorkPlanObjectKind.CUSTOMER) return

        _state.update {
            it.copy(page = WorkPlanPage.PICK_OBJECT, pickerQuery = "", pickerLoading = true)
        }

        viewModelScope.launch {
            when (val result = repository.plannableCustomers()) {
                is DataResult.Success -> _state.update {
                    it.copy(pickerLoading = false, pickable = result.data)
                }

                is DataResult.Failure -> _state.update {
                    it.copy(pickerLoading = false, error = "Không tải được danh sách khách hàng")
                }
            }
        }
    }

    fun onClosePicker() = _state.update {
        it.copy(page = WorkPlanPage.EDIT, pickerQuery = "")
    }

    fun onPickerQuery(value: String) = _state.update { it.copy(pickerQuery = value) }

    fun onToggleObject(target: WorkPlanObject) = _state.update {
        it.copy(draft = it.draft.toggleObject(target), error = null)
    }

    fun onRemoveObject(targetId: String) = _state.update {
        it.copy(draft = it.draft.withoutObject(targetId), error = null)
    }

    // -------------------------------------------------------------------------
    // Saving and closing out
    // -------------------------------------------------------------------------

    fun save() {
        val current = _state.value
        if (!current.canSave) return

        _state.update { it.copy(working = true, error = null) }

        viewModelScope.launch {
            when (val result = repository.save(current.draft)) {
                is DataResult.Success -> {
                    _state.update {
                        it.copy(
                            working = false,
                            page = WorkPlanPage.DETAIL,
                            detail = result.data,
                            draft = DraftWorkPlan(),
                            message = "Đã lưu kế hoạch",
                        )
                    }
                    load()
                }

                is DataResult.Failure -> _state.update {
                    it.copy(working = false, error = "Không lưu được kế hoạch")
                }
            }
        }
    }

    fun onSubmitForApproval() = transition(WorkPlanStatus.SUBMITTED, null, "Đã gửi duyệt")

    fun onComplete(result: String) =
        transition(WorkPlanStatus.COMPLETED, result, "Đã hoàn thành kế hoạch")

    fun onCancelPlan(reason: String) =
        transition(WorkPlanStatus.CANCELLED, reason, "Đã hủy kế hoạch")

    private fun transition(status: WorkPlanStatus, reason: String?, done: String) {
        val plan = _state.value.detail?.plan ?: return
        if (_state.value.working) return

        _state.update { it.copy(working = true, error = null) }

        viewModelScope.launch {
            when (val result = repository.setStatus(plan.id, status, reason)) {
                is DataResult.Success -> {
                    _state.update {
                        it.copy(working = false, detail = result.data, message = done)
                    }
                    load()
                }

                // The server's own refusals are the interesting ones here — a
                // plan whose state moved under the rep, most often — so its
                // message is what gets shown rather than a generic one.
                is DataResult.Failure -> _state.update {
                    it.copy(working = false, error = result.error.transitionMessage())
                }
            }
        }
    }

    fun onDelete() {
        val plan = _state.value.detail?.plan ?: return
        if (!plan.canEdit || _state.value.working) return

        _state.update { it.copy(working = true, error = null) }

        viewModelScope.launch {
            when (repository.delete(plan.id)) {
                is DataResult.Success -> {
                    _state.update {
                        it.copy(
                            working = false,
                            page = WorkPlanPage.LIST,
                            detail = null,
                            message = "Đã xóa kế hoạch nháp",
                        )
                    }
                    load()
                }

                is DataResult.Failure -> _state.update {
                    it.copy(working = false, error = "Không xóa được kế hoạch")
                }
            }
        }
    }
}

/**
 * The server's own words when it has any.
 *
 * A refused transition is the interesting failure here — a plan whose state
 * moved under the rep, most often — and Postgres already says exactly why. A
 * generic "không đổi được trạng thái" would throw that away.
 */
private fun AppError.transitionMessage(): String = when (this) {
    is AppError.Network -> "Không có kết nối mạng"
    is AppError.Auth -> "Phiên đăng nhập đã hết hạn, đăng nhập lại"
    is AppError.Server -> message ?: "Không đổi được trạng thái"
    is AppError.Rule -> message ?: "Không đổi được trạng thái"
    is AppError.Unknown -> message ?: "Không đổi được trạng thái"
}
