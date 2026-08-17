package com.tinhcd.myesalessfa.feature.workday

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Business
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tinhcd.myesalessfa.core.ui.ErrorBox
import com.tinhcd.myesalessfa.core.ui.LoadingBox
import com.tinhcd.myesalessfa.core.ui.PrimaryButton
import com.tinhcd.myesalessfa.core.ui.theme.MyeSalesTheme
import com.tinhcd.myesalessfa.core.ui.theme.brand
import com.tinhcd.myesalessfa.domain.model.Branch
import com.tinhcd.myesalessfa.domain.model.CheckInGate
import com.tinhcd.myesalessfa.domain.model.ReasonCode
import com.tinhcd.myesalessfa.domain.model.ReasonKind
import com.tinhcd.myesalessfa.domain.model.WorkDay
import com.tinhcd.myesalessfa.domain.model.WorkDayPolicy
import com.tinhcd.myesalessfa.domain.model.WorkDayState
import com.tinhcd.myesalessfa.feature.checkin.prompt
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/**
 * The depot: where the selling day is opened and where it is closed.
 *
 * Both punches share this screen, and which one is offered is read off the day
 * rather than passed in — a rep who left this screen open while a colleague's
 * phone did nothing should not be able to close a day twice from a stale button.
 */
@Composable
fun WorkDayScreen(
    onDone: () -> Unit,
    viewModel: WorkDayViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(state.finished) {
        if (state.finished) onDone()
    }

    WorkDayContent(
        state = state,
        onBack = onDone,
        onRetry = viewModel::load,
        onRefreshLocation = viewModel::refreshLocation,
        onSelectReason = viewModel::selectReason,
        onSubmit = viewModel::submit,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WorkDayContent(
    state: WorkDayUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onRefreshLocation: () -> Unit,
    onSelectReason: (ReasonCode) -> Unit,
    onSubmit: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (state.closing) "Kết thúc ngày" else "Bắt đầu ngày") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.brand.header,
                    titleContentColor = MaterialTheme.brand.onHeader,
                ),
            )
        },
    ) { padding ->
        val day = state.day
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                state.loading -> LoadingBox()
                day == null -> ErrorBox(
                    state.error ?: "Không tải được thông tin chấm công",
                    onRetry = onRetry,
                )

                else -> Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    BranchCard(branch = day.branch)
                    DayCard(day = day, lateAfter = state.policy.lateAfter)

                    LocationLine(
                        gate = state.gate,
                        locating = state.locating,
                        onRefresh = onRefreshLocation,
                    )

                    if (state.needsReason) {
                        ReasonPicker(
                            gate = state.gate,
                            reasons = state.reasons,
                            selected = state.selectedReason,
                            onSelect = onSelectReason,
                        )
                    }

                    // Only ever the server's own refusal or a failed read. Both are
                    // things the rep can act on, so neither is swallowed.
                    state.error?.let { Notice(it) }

                    FillGap()

                    if (state.closing && !day.canCloseDay) {
                        Notice(
                            "Còn ${day.openVisits} cuộc viếng thăm chưa check-out. " +
                                "Đóng các cuộc đó trước khi kết thúc ngày.",
                        )
                    }

                    if (day.state == WorkDayState.CLOSED) {
                        Text(
                            text = "Ngày bán hàng hôm nay đã kết thúc.",
                            style = MaterialTheme.typography.bodyLarge,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        PrimaryButton(
                            text = if (state.closing) "Kết thúc ngày bán hàng" else "Bắt đầu ngày bán hàng",
                            onClick = onSubmit,
                            enabled = state.canSubmit,
                            loading = state.submitting,
                        )
                    }

                    OutlinedButton(
                        onClick = onBack,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Quay lại") }
                }
            }
        }
    }
}

/** Pushes the buttons to the bottom without a magic height. */
@Composable
private fun ColumnScope.FillGap() {
    Box(Modifier.weight(1f))
}

@Composable
private fun BranchCard(branch: Branch) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.Business,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Column(Modifier.padding(start = 14.dp)) {
                Text(
                    text = branch.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = listOfNotNull(branch.code, branch.address).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * The day so far. Shows the clock-in time once there is one, because that is the
 * figure the rep will be asked about, and flags it against the configured hour
 * rather than leaving them to compare two numbers themselves.
 */
@Composable
private fun DayCard(day: WorkDay, lateAfter: LocalTime?) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = DateFormat.format(day.date),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            PunchLine(
                label = "Bắt đầu",
                epochMs = day.checkInAtEpochMs,
                late = day.isLate(lateAfter),
            )
            PunchLine(label = "Kết thúc", epochMs = day.checkOutAtEpochMs, late = false)
        }
    }
}

@Composable
private fun PunchLine(label: String, epochMs: Long?, late: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            Icons.Default.Schedule,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 8.dp).weight(1f),
        )
        Text(
            text = epochMs?.let(::clockOf) ?: "—",
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
        )
        if (late) {
            Surface(
                color = LateAmber.copy(alpha = 0.16f),
                contentColor = LateAmber,
                shape = CircleShape,
                modifier = Modifier.padding(start = 8.dp),
            ) {
                Text(
                    text = "muộn",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun LocationLine(gate: CheckInGate?, locating: Boolean, onRefresh: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val (text, color) = when {
        locating -> "Đang lấy vị trí..." to scheme.onSurfaceVariant
        gate is CheckInGate.Allowed ->
            "Trong bán kính cho phép (${gate.distanceM.roundToInt()} m)" to scheme.primary

        gate is CheckInGate.NeedsReason -> {
            val d = gate.distanceM?.let { " (${it.roundToInt()} m)" }.orEmpty()
            "Cần chọn lý do$d" to scheme.secondary
        }

        gate is CheckInGate.Blocked -> "Không thể chấm công tại vị trí này" to scheme.error
        else -> "Chưa xác định vị trí" to scheme.onSurfaceVariant
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        if (locating) {
            CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
        } else {
            Icon(
                Icons.Default.LocationOn,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(16.dp),
            )
        }
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = color,
            modifier = Modifier.padding(horizontal = 8.dp).weight(1f),
        )
        OutlinedButton(onClick = onRefresh, enabled = !locating) { Text("Làm mới") }
    }
}

@Composable
private fun ReasonPicker(
    gate: CheckInGate?,
    reasons: List<ReasonCode>,
    selected: ReasonCode?,
    onSelect: (ReasonCode) -> Unit,
) {
    val kind = (gate as? CheckInGate.NeedsReason)?.kind ?: ReasonKind.GPS_UNAVAILABLE
    Column {
        Text(
            text = kind.prompt(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (reasons.isEmpty()) {
            Notice("Chưa tải được danh sách lý do. Kiểm tra kết nối rồi thử lại.")
        }
        reasons.forEach { reason ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(selected = reason == selected, onClick = { onSelect(reason) })
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = reason == selected, onClick = { onSelect(reason) })
                Text(reason.name, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
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

private val LateAmber = Color(0xFFF5A202)

private val DateFormat = DateTimeFormatter.ofPattern("dd/MM/yyyy")

private val ClockFormat = DateTimeFormatter.ofPattern("HH:mm")

private fun clockOf(epochMs: Long): String = ClockFormat
    .format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))

/**
 * Late only ever means the start, and only when the market configured an hour to
 * be late against. No setting means no judgement.
 */
private fun WorkDay.isLate(lateAfter: LocalTime?): Boolean {
    val start = checkInAtEpochMs ?: return false
    if (lateAfter == null) return false
    return Instant.ofEpochMilli(start).atZone(ZoneId.systemDefault())
        .toLocalTime().isAfter(lateAfter)
}

// -----------------------------------------------------------------------------
// Previews
// -----------------------------------------------------------------------------

private val SampleBranch = Branch(
    id = "b1",
    code = "CN01",
    name = "Nhà phân phối Minh Phát",
    address = "45 Lý Thường Kiệt, Quận 10",
    lat = 10.772,
    lng = 106.658,
)

private fun sampleDay(
    checkIn: Long? = null,
    checkOut: Long? = null,
    openVisits: Int = 0,
) = WorkDay(
    date = LocalDate.of(2026, 8, 17),
    branch = SampleBranch,
    checkInAtEpochMs = checkIn,
    checkOutAtEpochMs = checkOut,
    openVisits = openVisits,
)

private val SamplePolicy = WorkDayPolicy(
    branchRadiusM = 200,
    maxAccuracyM = 50,
    allowReasonWhenFar = true,
    lateAfter = LocalTime.of(8, 30),
)

@Preview(name = "Chấm công - bắt đầu ngày", showBackground = true, heightDp = 800)
@Composable
private fun WorkDayStartPreview() {
    MyeSalesTheme {
        WorkDayContent(
            state = WorkDayUiState(
                loading = false,
                day = sampleDay(),
                policy = SamplePolicy,
                gate = CheckInGate.Allowed(distanceM = 42.0),
            ),
            onBack = {},
            onRetry = {},
            onRefreshLocation = {},
            onSelectReason = {},
            onSubmit = {},
        )
    }
}

/** The day cannot be closed: two shops are still open, and it says which count. */
@Preview(name = "Chấm công - còn viếng thăm mở", showBackground = true, heightDp = 800)
@Composable
private fun WorkDayBlockedPreview() {
    MyeSalesTheme {
        WorkDayContent(
            state = WorkDayUiState(
                loading = false,
                day = sampleDay(checkIn = 1_755_400_000_000, openVisits = 2),
                policy = SamplePolicy,
                gate = CheckInGate.Allowed(distanceM = 12.0),
            ),
            onBack = {},
            onRetry = {},
            onRefreshLocation = {},
            onSelectReason = {},
            onSubmit = {},
        )
    }
}
