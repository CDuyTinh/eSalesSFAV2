package com.tinhcd.myesalessfa.feature.workplan

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.School
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tinhcd.myesalessfa.core.ui.ErrorBox
import com.tinhcd.myesalessfa.core.ui.LoadingBox
import com.tinhcd.myesalessfa.core.ui.theme.MyeSalesTheme
import com.tinhcd.myesalessfa.core.ui.theme.brand
import com.tinhcd.myesalessfa.domain.model.DraftWorkPlan
import com.tinhcd.myesalessfa.domain.model.WorkPlan
import com.tinhcd.myesalessfa.domain.model.WorkPlanBoard
import com.tinhcd.myesalessfa.domain.model.WorkPlanDay
import com.tinhcd.myesalessfa.domain.model.WorkPlanDetail
import com.tinhcd.myesalessfa.domain.model.WorkPlanEvent
import com.tinhcd.myesalessfa.domain.model.WorkPlanObject
import com.tinhcd.myesalessfa.domain.model.WorkPlanObjectKind
import com.tinhcd.myesalessfa.domain.model.WorkPlanStatus
import com.tinhcd.myesalessfa.domain.model.WorkWithCategory
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.YearMonth

/**
 * Kế hoạch làm việc — the legacy's MY_TASK and WORKING_PLAN in one place.
 *
 * A month calendar over a filtered list, because a plan's first question is
 * always "which day" and its second is "what state is it in". The scope switch
 * is what used to be two separate menu entries.
 */
@Composable
fun WorkPlanScreen(
    onBack: () -> Unit,
    viewModel: WorkPlanViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    when (state.page) {
        WorkPlanPage.LIST -> ListPage(
            state = state,
            onBack = onBack,
            onRetry = viewModel::load,
            onMonth = viewModel::onMonth,
            onScope = viewModel::onScope,
            onStatusFilter = viewModel::onStatusFilter,
            onDay = viewModel::onDay,
            onOpen = viewModel::onOpen,
            onNew = viewModel::onNew,
            onDismissMessage = viewModel::dismissMessage,
        )

        WorkPlanPage.DETAIL -> DetailPage(
            state = state,
            onBack = viewModel::onBackToList,
            onEdit = viewModel::onEdit,
            onSubmit = viewModel::onSubmitForApproval,
            onComplete = viewModel::onComplete,
            onCancelPlan = viewModel::onCancelPlan,
            onDelete = viewModel::onDelete,
            onDismissMessage = viewModel::dismissMessage,
        )

        WorkPlanPage.EDIT -> EditPage(
            state = state,
            onBack = viewModel::onCancelEdit,
            onCategory = viewModel::onCategory,
            onContent = viewModel::onContent,
            onNote = viewModel::onNote,
            onPlace = viewModel::onPlace,
            onWindow = viewModel::onWindow,
            onOpenPicker = viewModel::onOpenPicker,
            onRemoveObject = viewModel::onRemoveObject,
            onSave = viewModel::save,
        )

        WorkPlanPage.PICK_OBJECT -> PickerPage(
            state = state,
            onBack = viewModel::onClosePicker,
            onQuery = viewModel::onPickerQuery,
            onToggle = viewModel::onToggleObject,
        )
    }
}

// -----------------------------------------------------------------------------
// The list
// -----------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ListPage(
    state: WorkPlanUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onMonth: (YearMonth) -> Unit,
    onScope: (WorkPlanScope) -> Unit,
    onStatusFilter: (WorkPlanStatus?) -> Unit,
    onDay: (LocalDate?) -> Unit,
    onOpen: (WorkPlan) -> Unit,
    onNew: () -> Unit,
    onDismissMessage: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Kế hoạch làm việc") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Quay lại")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.brand.header,
                    titleContentColor = MaterialTheme.brand.onHeader,
                    navigationIconContentColor = MaterialTheme.brand.onHeader,
                ),
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onNew) {
                Icon(Icons.Default.Add, contentDescription = "Lập kế hoạch")
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                state.loading && state.board.items.isEmpty() -> LoadingBox()
                state.error != null && state.board.items.isEmpty() ->
                    ErrorBox(state.error, onRetry = onRetry)

                else -> LazyColumn(
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item {
                        ScopeRow(selected = state.scope, onScope = onScope)
                    }

                    item {
                        MonthCard(
                            month = state.month,
                            calendar = state.board.calendar,
                            selected = state.selectedDay,
                            onMonth = onMonth,
                            onDay = onDay,
                        )
                    }

                    item {
                        StatusRow(
                            board = state.board,
                            selected = state.statusFilter,
                            onStatusFilter = onStatusFilter,
                        )
                    }

                    if (state.message != null || state.error != null) {
                        item {
                            Notice(
                                text = state.error ?: state.message.orEmpty(),
                                isError = state.error != null,
                                onDismiss = onDismissMessage,
                            )
                        }
                    }

                    if (state.visible.isEmpty()) {
                        item { EmptyList(hasDay = state.selectedDay != null) }
                    } else {
                        items(state.visible, key = { it.id }) { plan ->
                            PlanCard(plan = plan, onClick = { onOpen(plan) })
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScopeRow(selected: WorkPlanScope, onScope: (WorkPlanScope) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        WorkPlanScope.entries.forEachIndexed { index, scope ->
            SegmentedButton(
                selected = selected == scope,
                onClick = { onScope(scope) },
                shape = SegmentedButtonDefaults.itemShape(index, WorkPlanScope.entries.size),
            ) {
                Text(scope.label)
            }
        }
    }
}

/**
 * The month grid.
 *
 * Each cell is painted from the day's most urgent status, which is what the
 * server's calendar already decided — the client does not re-rank, so the grid
 * and the list cannot disagree about what a day is.
 */
@Composable
private fun MonthCard(
    month: YearMonth,
    calendar: List<WorkPlanDay>,
    selected: LocalDate?,
    onMonth: (YearMonth) -> Unit,
    onDay: (LocalDate?) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { onMonth(month.minusMonths(1)) }) {
                    Icon(Icons.Default.ChevronLeft, contentDescription = "Tháng trước")
                }
                Text(
                    "Tháng ${month.monthValue}/${month.year}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                IconButton(onClick = { onMonth(month.plusMonths(1)) }) {
                    Icon(Icons.Default.ChevronRight, contentDescription = "Tháng sau")
                }
            }

            Row(Modifier.fillMaxWidth()) {
                listOf("T2", "T3", "T4", "T5", "T6", "T7", "CN").forEach {
                    Text(
                        it,
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }

            // Monday-first, so the leading blanks are however far into the week
            // the first of the month falls.
            val lead = month.atDay(1).dayOfWeek.value - 1
            val days = month.lengthOfMonth()
            val cells = lead + days
            val rows = (cells + 6) / 7

            repeat(rows) { row ->
                Row(Modifier.fillMaxWidth()) {
                    repeat(7) { col ->
                        val index = row * 7 + col
                        val dayOfMonth = index - lead + 1
                        Box(Modifier.weight(1f).aspectRatio(1f), Alignment.Center) {
                            if (dayOfMonth in 1..days) {
                                val date = month.atDay(dayOfMonth)
                                DayCell(
                                    date = date,
                                    entry = calendar.firstOrNull { it.day == date.toString() },
                                    isSelected = selected == date,
                                    onClick = { onDay(date) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DayCell(
    date: LocalDate,
    entry: WorkPlanDay?,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val tint = entry?.status?.let(::statusColour)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(2.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(
                when {
                    isSelected -> MaterialTheme.colorScheme.primary
                    tint != null -> tint.copy(alpha = 0.18f)
                    else -> Color.Transparent
                },
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                date.dayOfMonth.toString(),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = if (entry != null) FontWeight.Bold else FontWeight.Normal,
                color = if (isSelected) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
            if (tint != null) {
                Surface(
                    shape = CircleShape,
                    color = if (isSelected) MaterialTheme.colorScheme.onPrimary else tint,
                    modifier = Modifier.size(5.dp),
                ) {}
            }
        }
    }
}

@Composable
private fun StatusRow(
    board: WorkPlanBoard,
    selected: WorkPlanStatus?,
    onStatusFilter: (WorkPlanStatus?) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = selected == null,
            onClick = { onStatusFilter(null) },
            label = { Text("Tất cả ${board.total}") },
        )
        // Only statuses that occur. A chip reading zero is a button that does
        // nothing, and the legacy's fixed four-chip row had exactly that.
        board.presentStatuses.forEach { status ->
            FilterChip(
                selected = selected == status,
                onClick = { onStatusFilter(status) },
                label = { Text("${status.label} ${board.count(status)}") },
            )
        }
    }
}

@Composable
private fun PlanCard(plan: WorkPlan, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    plan.content,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                StatusPill(plan.status)
            }

            Text(
                plan.categoryName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Label(Icons.Default.CalendarMonth, windowLabel(plan.fromAt, plan.toAt))
                if (plan.objectCount > 0) {
                    Label(Icons.Default.Groups, plan.objectCount.toString())
                }
                if (plan.fieldTraining) {
                    Label(Icons.Default.School, "Kèm cặp")
                }
            }

            if (plan.assignedToMe) {
                Text(
                    "Được giao",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

// -----------------------------------------------------------------------------
// One plan
// -----------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailPage(
    state: WorkPlanUiState,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onSubmit: () -> Unit,
    onComplete: (String) -> Unit,
    onCancelPlan: (String) -> Unit,
    onDelete: () -> Unit,
    onDismissMessage: () -> Unit,
) {
    val detail = state.detail
    var closing by remember { mutableStateOf<WorkPlanStatus?>(null) }
    var confirmingDelete by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Chi tiết kế hoạch") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Quay lại")
                    }
                },
                actions = {
                    if (detail?.plan?.canEdit == true) {
                        IconButton(onClick = onEdit) {
                            Icon(Icons.Default.Edit, contentDescription = "Sửa")
                        }
                        IconButton(onClick = { confirmingDelete = true }) {
                            Icon(Icons.Default.DeleteOutline, contentDescription = "Xóa")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.brand.header,
                    titleContentColor = MaterialTheme.brand.onHeader,
                    navigationIconContentColor = MaterialTheme.brand.onHeader,
                    actionIconContentColor = MaterialTheme.brand.onHeader,
                ),
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (detail == null) {
                LoadingBox()
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (state.message != null || state.error != null) {
                        item {
                            Notice(
                                text = state.error ?: state.message.orEmpty(),
                                isError = state.error != null,
                                onDismiss = onDismissMessage,
                            )
                        }
                    }

                    item { HeadCard(detail) }

                    if (detail.objects.isNotEmpty()) {
                        item { SectionTitle("Đối tượng") }
                        items(detail.objects, key = { it.targetId }) { ObjectRow(it) }
                    }

                    if (detail.history.isNotEmpty()) {
                        item { SectionTitle("Diễn biến") }
                        item { HistoryCard(detail.history) }
                    }

                    item {
                        ActionRow(
                            plan = detail.plan,
                            working = state.working,
                            onSubmit = onSubmit,
                            onClose = { closing = it },
                        )
                    }
                }
            }
        }
    }

    closing?.let { status ->
        ReasonDialog(
            status = status,
            onDismiss = { closing = null },
            onConfirm = { text ->
                closing = null
                if (status == WorkPlanStatus.COMPLETED) onComplete(text) else onCancelPlan(text)
            },
        )
    }

    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text("Xóa kế hoạch nháp?") },
            text = { Text("Kế hoạch chưa gửi duyệt sẽ bị xóa hẳn.") },
            confirmButton = {
                TextButton(onClick = { confirmingDelete = false; onDelete() }) { Text("Xóa") }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDelete = false }) { Text("Bỏ qua") }
            },
        )
    }
}

@Composable
private fun HeadCard(detail: WorkPlanDetail) {
    val plan = detail.plan
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    plan.content,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                StatusPill(plan.status)
            }

            Field("Loại công việc", plan.categoryName)
            Field("Thời gian", windowLabel(plan.fromAt, plan.toAt))
            plan.place?.takeIf { it.isNotBlank() }?.let { Field("Địa điểm", it) }
            plan.note?.takeIf { it.isNotBlank() }?.let { Field("Ghi chú", it) }
            plan.result?.takeIf { it.isNotBlank() }?.let { Field("Kết quả", it) }
            plan.reason?.takeIf { it.isNotBlank() }?.let { Field("Lý do", it) }

            if (plan.assignedToMe) {
                Text(
                    "Kế hoạch được giao — bạn thực hiện, không sửa nội dung",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun ObjectRow(target: WorkPlanObject) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(target.name, style = MaterialTheme.typography.bodyMedium)
            val sub = listOfNotNull(target.code, target.address)
                .filter { it.isNotBlank() }
                .joinToString(" · ")
            if (sub.isNotBlank()) {
                Text(
                    sub,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun HistoryCard(history: List<WorkPlanEvent>) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            history.forEach { event ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Surface(
                        shape = CircleShape,
                        color = statusColour(event.status),
                        modifier = Modifier.size(8.dp).padding(top = 4.dp),
                    ) {}
                    Column(Modifier.weight(1f)) {
                        Text(event.status.label, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            listOfNotNull(dayLabel(event.at), event.actor).joinToString(" · "),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                        event.reason?.takeIf { it.isNotBlank() }?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ActionRow(
    plan: WorkPlan,
    working: Boolean,
    onSubmit: () -> Unit,
    onClose: (WorkPlanStatus) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (plan.canSubmit) {
            FilledTonalButton(
                onClick = onSubmit,
                enabled = !working,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Gửi duyệt")
            }
        }

        if (plan.canClose) {
            FilledTonalButton(
                onClick = { onClose(WorkPlanStatus.COMPLETED) },
                enabled = !working,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Hoàn thành")
            }
        }

        if (plan.canCancel) {
            OutlinedButton(
                onClick = { onClose(WorkPlanStatus.CANCELLED) },
                enabled = !working,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Hủy kế hoạch")
            }
        }
    }
}

/** Completing and cancelling both need words, which is the legacy's own rule. */
@Composable
private fun ReasonDialog(
    status: WorkPlanStatus,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf("") }
    val completing = status == WorkPlanStatus.COMPLETED

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (completing) "Hoàn thành kế hoạch" else "Hủy kế hoạch") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(if (completing) "Kết quả" else "Lý do hủy") },
                minLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(text) },
                enabled = text.isNotBlank(),
            ) {
                Text("Xác nhận")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Bỏ qua") } },
    )
}

// -----------------------------------------------------------------------------
// The form
// -----------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditPage(
    state: WorkPlanUiState,
    onBack: () -> Unit,
    onCategory: (WorkWithCategory) -> Unit,
    onContent: (String) -> Unit,
    onNote: (String) -> Unit,
    onPlace: (String) -> Unit,
    onWindow: (String, String) -> Unit,
    onOpenPicker: () -> Unit,
    onRemoveObject: (String) -> Unit,
    onSave: () -> Unit,
) {
    val draft = state.draft

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (draft.id == null) "Lập kế hoạch" else "Sửa kế hoạch") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Quay lại")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.brand.header,
                    titleContentColor = MaterialTheme.brand.onHeader,
                    navigationIconContentColor = MaterialTheme.brand.onHeader,
                ),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            state.error?.let {
                item { Notice(text = it, isError = true, onDismiss = {}) }
            }

            item { SectionTitle("Loại công việc") }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    state.board.categories.forEach { category ->
                        FilterChip(
                            selected = draft.category?.id == category.id,
                            onClick = { onCategory(category) },
                            label = { Text(category.name) },
                        )
                    }
                }
            }

            item {
                OutlinedTextField(
                    value = draft.content,
                    onValueChange = onContent,
                    label = { Text("Nội dung") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            item {
                DayAndTime(
                    fromAt = draft.fromAt,
                    toAt = draft.toAt,
                    onWindow = onWindow,
                )
            }

            item {
                OutlinedTextField(
                    value = draft.place,
                    onValueChange = onPlace,
                    label = { Text("Địa điểm") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            item {
                OutlinedTextField(
                    value = draft.note,
                    onValueChange = onNote,
                    label = { Text("Ghi chú") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // Only the kinds this app can actually pick from get a picker; the
            // rest say why rather than opening an empty list.
            when (draft.objectKind) {
                WorkPlanObjectKind.CUSTOMER -> {
                    item {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            SectionTitle("Khách hàng (${draft.objects.size})")
                            TextButton(onClick = onOpenPicker) { Text("Chọn") }
                        }
                    }
                    items(draft.objects, key = { it.targetId }) { target ->
                        Card(Modifier.fillMaxWidth()) {
                            Row(
                                Modifier.fillMaxWidth().padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(target.name, style = MaterialTheme.typography.bodyMedium)
                                    target.address?.takeIf { it.isNotBlank() }?.let {
                                        Text(
                                            it,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                                IconButton(onClick = { onRemoveObject(target.targetId) }) {
                                    Icon(Icons.Default.DeleteOutline, contentDescription = "Bỏ")
                                }
                            }
                        }
                    }
                }

                WorkPlanObjectKind.SALESPERSON, WorkPlanObjectKind.BRANCH -> item {
                    Notice(
                        text = "Loại công việc này cần chọn ${draft.objectKind?.label?.lowercase()}" +
                            ", chức năng chọn chưa có trong bản này.",
                        isError = false,
                        onDismiss = {},
                    )
                }

                null -> Unit
            }

            item {
                FilledTonalButton(
                    onClick = onSave,
                    enabled = state.canSave,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Lưu")
                }
            }
        }
    }
}

/**
 * The window, as a day plus two hours.
 *
 * Kept as plain text rather than a picker dialog: the form stores ISO strings
 * and a wheel picker here would be a second representation to keep in step for
 * no gain the rep can feel.
 */
@Composable
private fun DayAndTime(fromAt: String, toAt: String, onWindow: (String, String) -> Unit) {
    // Shown and rebuilt in the rep's own time. A plan edited after saving comes
    // back from the server as UTC, so reading the characters off the string
    // would show a Vietnamese 08:00 as 01:00 and then save it that way.
    val day = localDate(fromAt)
    val from = clockLabel(fromAt)
    val to = clockLabel(toAt)

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = day,
                onValueChange = { next ->
                    if (next.length == 10) {
                        onWindow(atLocal(next, from), atLocal(next, to))
                    }
                },
                label = { Text("Ngày (yyyy-MM-dd)") },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = from,
                    onValueChange = { next ->
                        if (next.length == 5) onWindow(atLocal(day, next), toAt)
                    },
                    label = { Text("Từ") },
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = to,
                    onValueChange = { next ->
                        if (next.length == 5) onWindow(fromAt, atLocal(day, next))
                    },
                    label = { Text("Đến") },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** The clock face a timestamp shows on the rep's phone. */
private fun clockLabel(iso: String): String = runCatching {
    OffsetDateTime.parse(iso)
        .atZoneSameInstant(ZoneId.systemDefault())
        .format(ClockFormat)
}.getOrDefault(iso.drop(11).take(5))

/** A day and a clock face back into an instant, carrying the device's offset. */
private fun atLocal(day: String, clock: String): String = runCatching {
    LocalDate.parse(day)
        .atTime(clock.take(2).toInt(), clock.takeLast(2).toInt())
        .atZone(ZoneId.systemDefault())
        .toOffsetDateTime()
        .toString()
}.getOrDefault("${day}T$clock:00+07:00")

private val ClockFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PickerPage(
    state: WorkPlanUiState,
    onBack: () -> Unit,
    onQuery: (String) -> Unit,
    onToggle: (WorkPlanObject) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Chọn khách hàng") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Xong")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.brand.header,
                    titleContentColor = MaterialTheme.brand.onHeader,
                    navigationIconContentColor = MaterialTheme.brand.onHeader,
                ),
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = state.pickerQuery,
                onValueChange = onQuery,
                label = { Text("Tìm theo tên hoặc mã") },
                modifier = Modifier.fillMaxWidth().padding(12.dp),
            )

            if (state.pickerLoading) {
                LoadingBox()
            } else {
                val chosen = state.draft.objects.map { it.targetId }.toSet()
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(state.visiblePickable, key = { it.targetId }) { target ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onToggle(target) },
                            colors = CardDefaults.cardColors(
                                containerColor = if (target.targetId in chosen) {
                                    MaterialTheme.colorScheme.secondaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surface
                                },
                            ),
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                Text(target.name, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    listOfNotNull(target.code, target.address)
                                        .filter { it.isNotBlank() }
                                        .joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// -----------------------------------------------------------------------------
// Small pieces
// -----------------------------------------------------------------------------

@Composable
private fun StatusPill(status: WorkPlanStatus) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = statusColour(status).copy(alpha = 0.16f),
    ) {
        Text(
            status.label,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = statusColour(status),
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun Label(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.outline,
        )
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

@Composable
private fun Field(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.width(96.dp),
        )
        Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
private fun Notice(text: String, isError: Boolean, onDismiss: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (isError) {
            MaterialTheme.colorScheme.errorContainer
        } else {
            MaterialTheme.colorScheme.secondaryContainer
        },
        modifier = Modifier.fillMaxWidth().clickable(onClick = onDismiss),
    ) {
        Text(
            text,
            modifier = Modifier.padding(12.dp),
            style = MaterialTheme.typography.bodySmall,
            color = if (isError) {
                MaterialTheme.colorScheme.onErrorContainer
            } else {
                MaterialTheme.colorScheme.onSecondaryContainer
            },
        )
    }
}

@Composable
private fun EmptyList(hasDay: Boolean) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            Icons.Default.CalendarMonth,
            contentDescription = null,
            modifier = Modifier.size(36.dp),
            tint = MaterialTheme.colorScheme.outline,
        )
        Text(
            if (hasDay) "Ngày này chưa có kế hoạch" else "Tháng này chưa có kế hoạch",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
    }
}

private fun statusColour(status: WorkPlanStatus): Color = when (status) {
    WorkPlanStatus.DRAFT -> Color(0xFF9E9E9E)
    WorkPlanStatus.SUBMITTED -> Color(0xFFF5A202)
    WorkPlanStatus.APPROVED -> Color(0xFF2E7D32)
    WorkPlanStatus.REJECTED -> Color(0xFFC62828)
    WorkPlanStatus.COMPLETED -> Color(0xFF1565C0)
    WorkPlanStatus.CANCELLED -> Color(0xFF616161)
}

/**
 * `2026-09-11T01:00:00+00:00` as `11/09 08:00`, in the rep's own time.
 *
 * Slicing the characters was wrong and looked right: the server sends UTC, so a
 * plan the rep entered as 08:00 came back reading 01:00. Parsed and shifted to
 * the device's zone instead, with the raw string as the fallback — a timestamp
 * this build cannot parse should show as it is rather than as a guess.
 */
private fun dayLabel(iso: String): String = runCatching {
    OffsetDateTime.parse(iso)
        .atZoneSameInstant(ZoneId.systemDefault())
        .format(DayAndTimeFormat)
}.getOrDefault(iso)

/** The local day a timestamp falls on, for comparing against a calendar cell. */
private fun localDate(iso: String): String = runCatching {
    OffsetDateTime.parse(iso).atZoneSameInstant(ZoneId.systemDefault()).toLocalDate().toString()
}.getOrDefault(iso.take(10))

private val DayAndTimeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM HH:mm")

private fun windowLabel(fromAt: String, toAt: String): String {
    val sameDay = localDate(fromAt) == localDate(toAt)
    return if (sameDay) {
        "${dayLabel(fromAt)} - ${dayLabel(toAt).takeLast(5)}"
    } else {
        "${dayLabel(fromAt)} → ${dayLabel(toAt)}"
    }
}


// -----------------------------------------------------------------------------

private val SampleCategories = listOf(
    WorkWithCategory(
        id = "1",
        code = "WW-ROUTE",
        name = "Đi tuyến cùng khách hàng",
        objectKind = WorkPlanObjectKind.CUSTOMER,
    ),
    WorkWithCategory(id = "2", code = "WW-TRAIN", name = "Đào tạo nội bộ"),
)

private val SampleBoard = WorkPlanBoard(
    items = listOf(
        WorkPlan(
            id = "1",
            categoryId = "1",
            categoryCode = "WW-ROUTE",
            categoryName = "Đi tuyến cùng khách hàng",
            content = "Đi tuyến cùng 2 khách hàng trọng điểm",
            fromAt = "2026-09-12T08:00:00+07:00",
            toAt = "2026-09-12T12:00:00+07:00",
            status = WorkPlanStatus.DRAFT,
            objectCount = 2,
        ),
        WorkPlan(
            id = "2",
            categoryId = "3",
            categoryCode = "WW-COACH",
            categoryName = "Kèm cặp nhân viên bán hàng",
            fieldTraining = true,
            content = "Kèm cặp tuyến quận 7",
            fromAt = "2026-09-15T08:00:00+07:00",
            toAt = "2026-09-15T17:00:00+07:00",
            status = WorkPlanStatus.APPROVED,
            assignedToMe = true,
        ),
    ),
    counts = mapOf(WorkPlanStatus.DRAFT to 1, WorkPlanStatus.APPROVED to 1),
    calendar = listOf(
        WorkPlanDay("2026-09-12", WorkPlanStatus.DRAFT, 1),
        WorkPlanDay("2026-09-15", WorkPlanStatus.APPROVED, 1),
    ),
    categories = SampleCategories,
    total = 2,
)

@Preview(name = "Kế hoạch - danh sách", showBackground = true, heightDp = 1000)
@Composable
private fun WorkPlanListPreview() {
    MyeSalesTheme {
        ListPage(
            state = WorkPlanUiState(loading = false, board = SampleBoard, month = YearMonth.of(2026, 9)),
            onBack = {},
            onRetry = {},
            onMonth = {},
            onScope = {},
            onStatusFilter = {},
            onDay = {},
            onOpen = {},
            onNew = {},
            onDismissMessage = {},
        )
    }
}

@Preview(name = "Kế hoạch - chi tiết", showBackground = true, heightDp = 800)
@Composable
private fun WorkPlanDetailPreview() {
    MyeSalesTheme {
        DetailPage(
            state = WorkPlanUiState(
                loading = false,
                board = SampleBoard,
                detail = WorkPlanDetail(
                    plan = SampleBoard.items[0],
                    objects = listOf(
                        WorkPlanObject(
                            kind = WorkPlanObjectKind.CUSTOMER,
                            targetId = "c1",
                            name = "Tạp hóa Bảy Hiền",
                            code = "KH001",
                            address = "12 Lý Thường Kiệt",
                        ),
                    ),
                    history = listOf(
                        WorkPlanEvent(WorkPlanStatus.DRAFT, "2026-09-11T09:12:00+07:00", "Nguyễn Văn A"),
                    ),
                ),
            ),
            onBack = {},
            onEdit = {},
            onSubmit = {},
            onComplete = {},
            onCancelPlan = {},
            onDelete = {},
            onDismissMessage = {},
        )
    }
}

@Preview(name = "Kế hoạch - lập mới", showBackground = true, heightDp = 1000)
@Composable
private fun WorkPlanEditPreview() {
    MyeSalesTheme {
        EditPage(
            state = WorkPlanUiState(
                loading = false,
                board = SampleBoard,
                draft = DraftWorkPlan(
                    category = SampleCategories[0],
                    content = "Đi tuyến cùng 2 khách hàng trọng điểm",
                    fromAt = "2026-09-12T08:00:00+07:00",
                    toAt = "2026-09-12T12:00:00+07:00",
                ),
            ),
            onBack = {},
            onCategory = {},
            onContent = {},
            onNote = {},
            onPlace = {},
            onWindow = { _, _ -> },
            onOpenPicker = {},
            onRemoveObject = {},
            onSave = {},
        )
    }
}
