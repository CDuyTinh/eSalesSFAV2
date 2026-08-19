package com.tinhcd.myesalessfa.feature.leave

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
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
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tinhcd.myesalessfa.core.ui.ErrorBox
import com.tinhcd.myesalessfa.core.ui.LoadingBox
import com.tinhcd.myesalessfa.domain.model.LeaveDraft
import com.tinhcd.myesalessfa.core.ui.theme.MyeSalesTheme
import com.tinhcd.myesalessfa.core.ui.theme.brand
import com.tinhcd.myesalessfa.domain.model.LeaveRequest
import com.tinhcd.myesalessfa.domain.model.LeaveStatus
import com.tinhcd.myesalessfa.domain.model.LeaveType
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Asking for time off, and seeing what came of it.
 *
 * There is no approve button anywhere here, and that is not an omission: this app
 * has no supervisor role, so deciding happens in the back office. The table
 * refuses any status change from a rep other than withdrawing something still
 * pending, which is the one move that is genuinely theirs.
 */
@Composable
fun LeaveScreen(
    onBack: () -> Unit,
    viewModel: LeaveViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LeaveContent(
        state = state,
        onBack = onBack,
        onRetry = viewModel::load,
        onStartAdding = viewModel::startAdding,
        onCancelAdding = viewModel::cancelAdding,
        onType = viewModel::onType,
        onFrom = viewModel::onFrom,
        onTo = viewModel::onTo,
        onReason = viewModel::onReason,
        onSubmit = viewModel::submit,
        onStartWithdrawing = viewModel::startWithdrawing,
        onCancelWithdrawing = viewModel::cancelWithdrawing,
        onConfirmWithdrawing = viewModel::confirmWithdrawing,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LeaveContent(
    state: LeaveUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onStartAdding: () -> Unit,
    onCancelAdding: () -> Unit,
    onType: (LeaveType) -> Unit,
    onFrom: (LocalDate) -> Unit,
    onTo: (LocalDate) -> Unit,
    onReason: (String) -> Unit,
    onSubmit: () -> Unit,
    onStartWithdrawing: (LeaveRequest) -> Unit,
    onCancelWithdrawing: () -> Unit,
    onConfirmWithdrawing: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Đơn xin nghỉ") },
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
                Icon(Icons.Default.Add, contentDescription = "Tạo đơn nghỉ")
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                state.loading -> LoadingBox()
                state.error != null && state.requests.isEmpty() ->
                    ErrorBox(state.error, onRetry = onRetry)

                state.requests.isEmpty() -> ErrorBox("Chưa có đơn xin nghỉ nào")

                else -> LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    state.error?.let { message -> item { Notice(message) } }

                    items(state.requests, key = { it.requestId }) { request ->
                        RequestCard(
                            request = request,
                            onWithdraw = { onStartWithdrawing(request) },
                        )
                    }
                }
            }
        }
    }

    state.draft?.let { draft ->
        LeaveFormDialog(
            draft = draft,
            types = state.types,
            busy = state.busy,
            error = state.error,
            onType = onType,
            onFrom = onFrom,
            onTo = onTo,
            onReason = onReason,
            onDismiss = onCancelAdding,
            onSubmit = onSubmit,
        )
    }

    state.withdrawing?.let { request ->
        AlertDialog(
            onDismissRequest = onCancelWithdrawing,
            confirmButton = {
                TextButton(onClick = onConfirmWithdrawing) { Text("Huỷ đơn") }
            },
            dismissButton = { TextButton(onClick = onCancelWithdrawing) { Text("Để nguyên") } },
            title = { Text("Huỷ đơn xin nghỉ?") },
            text = {
                Text("${request.typeName} · ${DateFormat.format(request.fromDate)} - " +
                    DateFormat.format(request.toDate))
            },
        )
    }
}

@Composable
private fun RequestCard(request: LeaveRequest, onWithdraw: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = request.typeName,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = "${DateFormat.format(request.fromDate)} - " +
                            "${DateFormat.format(request.toDate)} · ${request.days} ngày" +
                            if (request.isPaid) "" else " · không lương",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                StatusChip(request.status)
            }

            Spacer(Modifier.height(8.dp))
            Text(
                text = request.reason,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )

            // Why it was refused, or what was attached to the approval. The whole
            // reason a rep opens this screen after somebody has decided.
            request.decisionNote?.takeIf { it.isNotBlank() }?.let { note ->
                Spacer(Modifier.height(8.dp))
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Text(
                        text = "Phản hồi: $note",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(10.dp),
                    )
                }
            }

            if (request.canWithdraw) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onWithdraw) { Text("Huỷ đơn") }
            }
        }
    }
}

@Composable
private fun StatusChip(status: LeaveStatus) {
    val (label, colour) = when (status) {
        LeaveStatus.PENDING -> "Chờ duyệt" to PendingAmber
        LeaveStatus.APPROVED -> "Đã duyệt" to ApprovedGreen
        LeaveStatus.REJECTED -> "Từ chối" to RejectedRed
        LeaveStatus.CANCELLED -> "Đã huỷ" to CancelledGrey
    }

    Surface(color = colour, contentColor = Color.White, shape = CircleShape) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LeaveFormDialog(
    draft: LeaveDraft,
    types: List<LeaveType>,
    busy: Boolean,
    error: String?,
    onType: (LeaveType) -> Unit,
    onFrom: (LocalDate) -> Unit,
    onTo: (LocalDate) -> Unit,
    onReason: (String) -> Unit,
    onDismiss: () -> Unit,
    onSubmit: () -> Unit,
) {
    var picking by remember { mutableStateOf<DatePickTarget?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onSubmit, enabled = draft.canSubmit && !busy) { Text("Gửi đơn") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Huỷ") } },
        title = { Text("Tạo đơn xin nghỉ") },
        text = {
            Column {
                Text(
                    text = "Loại nghỉ",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    types.forEach { type ->
                        val active = type.leaveTypeId == draft.leaveTypeId
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
                            modifier = Modifier.clickable { onType(type) },
                        ) {
                            Text(
                                text = type.name,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            )
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
                Row {
                    DateField(
                        label = "Từ ngày",
                        date = draft.fromDate,
                        onClick = { picking = DatePickTarget.FROM },
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    DateField(
                        label = "Đến ngày",
                        date = draft.toDate,
                        onClick = { picking = DatePickTarget.TO },
                        modifier = Modifier.weight(1f),
                    )
                }

                draft.days?.let { days ->
                    Text(
                        text = "$days ngày",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                draft.periodError?.let { message ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = draft.reason,
                    onValueChange = onReason,
                    label = { Text("Lý do") },
                    modifier = Modifier.fillMaxWidth(),
                )

                error?.let { message ->
                    Spacer(Modifier.height(8.dp))
                    Notice(message)
                }
            }
        },
    )

    picking?.let { field ->
        val initial = when (field) {
            DatePickTarget.FROM -> draft.fromDate
            DatePickTarget.TO -> draft.toDate
        } ?: LocalDate.now()

        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = initial.atStartOfDay(ZoneOffset.UTC)
                .toInstant().toEpochMilli(),
        )

        DatePickerDialog(
            onDismissRequest = { picking = null },
            confirmButton = {
                TextButton(
                    onClick = {
                        pickerState.selectedDateMillis?.let { millis ->
                            val date = Instant.ofEpochMilli(millis)
                                .atZone(ZoneOffset.UTC).toLocalDate()
                            when (field) {
                                DatePickTarget.FROM -> onFrom(date)
                                DatePickTarget.TO -> onTo(date)
                            }
                        }
                        picking = null
                    },
                ) { Text("Chọn") }
            },
            dismissButton = { TextButton(onClick = { picking = null }) { Text("Huỷ") } },
        ) { DatePicker(state = pickerState) }
    }
}

/** Which of the two date boxes the picker is open for. */
private enum class DatePickTarget { FROM, TO }

@Composable
private fun DateField(
    label: String,
    date: LocalDate?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = date?.let { DateFormat.format(it) } ?: "",
        onValueChange = {},
        readOnly = true,
        enabled = false,
        label = { Text(label) },
        modifier = modifier.clickable(onClick = onClick),
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
            modifier = Modifier.padding(12.dp),
        )
    }
}

private val PendingAmber = Color(0xFFF5A202)
private val ApprovedGreen = Color(0xFF04A489)
private val RejectedRed = Color(0xFFD5262B)
private val CancelledGrey = Color(0xFF8A8A93)

private val DateFormat = DateTimeFormatter.ofPattern("dd/MM/yyyy")

// -----------------------------------------------------------------------------
// Previews
// -----------------------------------------------------------------------------

private val SampleRequests = listOf(
    LeaveRequest(
        requestId = "r1",
        leaveTypeId = "t1",
        typeName = "Nghỉ phép năm",
        isPaid = true,
        fromDate = LocalDate.of(2026, 8, 24),
        toDate = LocalDate.of(2026, 8, 26),
        reason = "Về quê có việc gia đình",
        status = LeaveStatus.PENDING,
        decisionNote = null,
        decidedAtEpochMs = null,
    ),
    LeaveRequest(
        requestId = "r2",
        leaveTypeId = "t3",
        typeName = "Nghỉ không lương",
        isPaid = false,
        fromDate = LocalDate.of(2026, 8, 3),
        toDate = LocalDate.of(2026, 8, 3),
        reason = "Việc riêng",
        status = LeaveStatus.REJECTED,
        decisionNote = "Trùng đợt kiểm kê, xin nghỉ ngày khác",
        decidedAtEpochMs = 1_754_200_000_000,
    ),
)

@Preview(name = "Đơn xin nghỉ", showBackground = true, heightDp = 800)
@Composable
private fun LeavePreview() {
    MyeSalesTheme {
        LeaveContent(
            state = LeaveUiState(loading = false, requests = SampleRequests),
            onBack = {},
            onRetry = {},
            onStartAdding = {},
            onCancelAdding = {},
            onType = {},
            onFrom = {},
            onTo = {},
            onReason = {},
            onSubmit = {},
            onStartWithdrawing = {},
            onCancelWithdrawing = {},
            onConfirmWithdrawing = {},
        )
    }
}
