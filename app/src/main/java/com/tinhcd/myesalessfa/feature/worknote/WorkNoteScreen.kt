package com.tinhcd.myesalessfa.feature.worknote

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tinhcd.myesalessfa.core.ui.ErrorBox
import com.tinhcd.myesalessfa.core.ui.LoadingBox
import com.tinhcd.myesalessfa.core.ui.theme.MyeSalesTheme
import com.tinhcd.myesalessfa.core.ui.theme.brand
import com.tinhcd.myesalessfa.domain.model.WorkNote
import com.tinhcd.myesalessfa.domain.model.WorkNoteStatus
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * The rep's own to-do list.
 *
 * Closing a note asks what came of it, and will not take an empty answer. A list
 * of ticked boxes with nothing written against them tells the rep who reads it in
 * three months exactly as much as no list at all — and that reader is usually the
 * person who wrote it.
 */
@Composable
fun WorkNoteScreen(
    onBack: () -> Unit,
    viewModel: WorkNoteViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    WorkNoteContent(
        state = state,
        onBack = onBack,
        onRetry = viewModel::load,
        onFilterChanged = viewModel::onFilterChanged,
        onStartAdding = viewModel::startAdding,
        onCancelAdding = viewModel::cancelAdding,
        onTitle = viewModel::onTitle,
        onBody = viewModel::onBody,
        onSaveDraft = viewModel::saveDraft,
        onStartCompleting = viewModel::startCompleting,
        onCancelCompleting = viewModel::cancelCompleting,
        onResult = viewModel::onResult,
        onConfirmCompleting = viewModel::confirmCompleting,
        onStartDeleting = viewModel::startDeleting,
        onCancelDeleting = viewModel::cancelDeleting,
        onConfirmDeleting = viewModel::confirmDeleting,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WorkNoteContent(
    state: WorkNoteUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onFilterChanged: (WorkNoteFilter) -> Unit,
    onStartAdding: () -> Unit,
    onCancelAdding: () -> Unit,
    onTitle: (String) -> Unit,
    onBody: (String) -> Unit,
    onSaveDraft: () -> Unit,
    onStartCompleting: (WorkNote) -> Unit,
    onCancelCompleting: () -> Unit,
    onResult: (String) -> Unit,
    onConfirmCompleting: () -> Unit,
    onStartDeleting: (WorkNote) -> Unit,
    onCancelDeleting: () -> Unit,
    onConfirmDeleting: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Ghi chú công việc") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Quay lại",
                        )
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
            FloatingActionButton(onClick = onStartAdding) {
                Icon(Icons.Default.Add, contentDescription = "Thêm ghi chú")
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            FilterRow(selected = state.filter, onSelect = onFilterChanged)

            if (state.overdueCount > 0) {
                Text(
                    text = "${state.overdueCount} việc đã quá hạn",
                    style = MaterialTheme.typography.labelSmall,
                    color = OverdueRed,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }

            Box(Modifier.fillMaxSize()) {
                when {
                    state.loading -> LoadingBox()
                    state.error != null && state.notes.isEmpty() ->
                        ErrorBox(state.error, onRetry = onRetry)

                    state.notes.isEmpty() -> ErrorBox(
                        when (state.filter) {
                            WorkNoteFilter.OPEN -> "Không có việc nào đang mở"
                            WorkNoteFilter.DONE -> "Chưa có việc nào hoàn thành"
                            WorkNoteFilter.ALL -> "Chưa có ghi chú nào"
                        },
                    )

                    else -> LazyColumn(
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        state.error?.let { message -> item { Notice(message) } }

                        items(state.notes, key = { it.noteId }) { note ->
                            NoteCard(
                                note = note,
                                today = state.today,
                                onComplete = { onStartCompleting(note) },
                                onDelete = { onStartDeleting(note) },
                            )
                        }
                    }
                }
            }
        }
    }

    state.draft?.let { draft ->
        AddDialog(
            title = draft.title,
            body = draft.body,
            titleError = draft.titleError,
            canSubmit = draft.canSubmit,
            busy = state.busy,
            onTitle = onTitle,
            onBody = onBody,
            onDismiss = onCancelAdding,
            onSave = onSaveDraft,
        )
    }

    state.completing?.let { completion ->
        CompleteDialog(
            result = completion.result,
            canSubmit = completion.canSubmit,
            busy = state.busy,
            onResult = onResult,
            onDismiss = onCancelCompleting,
            onConfirm = onConfirmCompleting,
        )
    }

    state.deleting?.let { note ->
        AlertDialog(
            onDismissRequest = onCancelDeleting,
            confirmButton = { TextButton(onClick = onConfirmDeleting) { Text("Xoá") } },
            dismissButton = { TextButton(onClick = onCancelDeleting) { Text("Huỷ") } },
            title = { Text("Xoá ghi chú?") },
            text = { Text(note.title) },
        )
    }
}

@Composable
private fun FilterRow(selected: WorkNoteFilter, onSelect: (WorkNoteFilter) -> Unit) {
    Row(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        WorkNoteFilter.entries.forEach { filter ->
            val active = filter == selected
            Surface(
                color = if (active) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
                contentColor = if (active) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                shape = CircleShape,
                modifier = Modifier.clickable { onSelect(filter) },
            ) {
                Text(
                    text = filter.label,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun NoteCard(
    note: WorkNote,
    today: LocalDate,
    onComplete: () -> Unit,
    onDelete: () -> Unit,
) {
    val overdue = note.isOverdue(today)

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = note.title,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        // Struck through once closed, so a finished list reads at
                        // a glance rather than needing the chips compared.
                        textDecoration = if (note.isOpen) null else TextDecoration.LineThrough,
                        color = if (note.isOpen) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    note.body?.takeIf { it.isNotBlank() }?.let { body ->
                        Text(
                            text = body,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                if (note.isOpen) {
                    IconButton(onClick = onComplete, modifier = Modifier.size(36.dp)) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = "Hoàn thành",
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.Default.DeleteOutline,
                        contentDescription = "Xoá",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            note.customerName?.let { name ->
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Storefront,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp),
                    )
                    Text(
                        text = name,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
            }

            note.dueOn?.let { due ->
                Spacer(Modifier.height(6.dp))
                Text(
                    text = when {
                        overdue -> "Quá hạn · ${DateFormat.format(due)}"
                        note.isDueToday(today) -> "Hạn hôm nay"
                        else -> "Hạn ${DateFormat.format(due)}"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (overdue) OverdueRed else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // The outcome, which is the reason closing one asks for anything.
            note.result?.takeIf { it.isNotBlank() }?.let { result ->
                Spacer(Modifier.height(8.dp))
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Text(
                        text = "Kết quả: $result",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(10.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun AddDialog(
    title: String,
    body: String,
    titleError: String?,
    canSubmit: Boolean,
    busy: Boolean,
    onTitle: (String) -> Unit,
    onBody: (String) -> Unit,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onSave, enabled = canSubmit && !busy) { Text("Lưu") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Huỷ") } },
        title = { Text("Thêm ghi chú") },
        text = {
            Column {
                OutlinedTextField(
                    value = title,
                    onValueChange = onTitle,
                    label = { Text("Nội dung công việc") },
                    singleLine = true,
                    isError = title.isNotEmpty() && titleError != null,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = body,
                    onValueChange = onBody,
                    label = { Text("Chi tiết (tuỳ chọn)") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
    )
}

@Composable
private fun CompleteDialog(
    result: String,
    canSubmit: Boolean,
    busy: Boolean,
    onResult: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = canSubmit && !busy) { Text("Hoàn thành") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Huỷ") } },
        title = { Text("Hoàn thành công việc") },
        text = {
            Column {
                Text(
                    text = "Ghi lại kết quả để sau này còn đọc được.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = result,
                    onValueChange = onResult,
                    label = { Text("Kết quả") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
    )
}

@Composable
private fun Notice(message: String) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Start,
            modifier = Modifier.padding(12.dp),
        )
    }
}

private val OverdueRed = Color(0xFFD5262B)

private val DateFormat = DateTimeFormatter.ofPattern("dd/MM/yyyy")

// -----------------------------------------------------------------------------
// Previews
// -----------------------------------------------------------------------------

private val SampleNotes = listOf(
    WorkNote(
        noteId = "n1",
        title = "Đòi kệ trưng bày cho Tạp hoá Bà Bảy",
        body = "Chủ shop hẹn tuần này trả lời",
        dueOn = LocalDate.of(2026, 8, 16),
        status = WorkNoteStatus.OPEN,
        result = null,
        doneAtEpochMs = null,
        createdAtEpochMs = null,
        customerId = "c1",
        customerName = "Tạp hoá Bà Bảy",
    ),
    WorkNote(
        noteId = "n2",
        title = "Gọi lại NPP về hàng thiếu",
        body = null,
        dueOn = LocalDate.of(2026, 8, 18),
        status = WorkNoteStatus.DONE,
        result = "NPP hẹn giao bù thứ Năm",
        doneAtEpochMs = 1_755_500_000_000,
        createdAtEpochMs = null,
        customerId = null,
        customerName = null,
    ),
)

@Preview(name = "Ghi chú công việc", showBackground = true, heightDp = 800)
@Composable
private fun WorkNotePreview() {
    MyeSalesTheme {
        WorkNoteContent(
            state = WorkNoteUiState(
                loading = false,
                filter = WorkNoteFilter.ALL,
                notes = SampleNotes,
            ),
            onBack = {},
            onRetry = {},
            onFilterChanged = {},
            onStartAdding = {},
            onCancelAdding = {},
            onTitle = {},
            onBody = {},
            onSaveDraft = {},
            onStartCompleting = {},
            onCancelCompleting = {},
            onResult = {},
            onConfirmCompleting = {},
            onStartDeleting = {},
            onCancelDeleting = {},
            onConfirmDeleting = {},
        )
    }
}
