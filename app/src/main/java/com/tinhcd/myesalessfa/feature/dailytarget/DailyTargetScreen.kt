package com.tinhcd.myesalessfa.feature.dailytarget

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tinhcd.myesalessfa.core.ui.ErrorBox
import com.tinhcd.myesalessfa.core.ui.LoadingBox
import com.tinhcd.myesalessfa.core.ui.PrimaryButton
import com.tinhcd.myesalessfa.core.ui.theme.MyeSalesTheme
import com.tinhcd.myesalessfa.core.ui.theme.brand
import com.tinhcd.myesalessfa.domain.model.DailyTargetPlan
import com.tinhcd.myesalessfa.domain.model.DailyTargetStop
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * What the rep means to sell today, outlet by outlet.
 *
 * Not the monthly target divided by its days — the Overview tab refuses to show
 * that on purpose, because it is a figure nobody agreed to. This is the rep's own
 * plan, typed in before they set out, and the total at the bottom is the day they
 * are choosing to have rather than one handed to them.
 */
@Composable
fun DailyTargetScreen(
    onBack: () -> Unit,
    viewModel: DailyTargetViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    DailyTargetContent(
        state = state,
        onBack = onBack,
        onRetry = viewModel::load,
        onAmountChanged = viewModel::onAmountChanged,
        onUseSuggestion = viewModel::useSuggestion,
        onSave = viewModel::save,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DailyTargetContent(
    state: DailyTargetUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onAmountChanged: (String, Long) -> Unit,
    onUseSuggestion: (DailyTargetStop) -> Unit,
    onSave: () -> Unit,
) {
    val plan = state.plan

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Chỉ tiêu ngày")
                        Text(
                            text = DateFormat.format(plan.date),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                },
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
        bottomBar = {
            if (!state.loading && plan.stops.isNotEmpty()) {
                Surface(shadowElevation = 8.dp) {
                    Column(Modifier.padding(16.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = "Tổng chỉ tiêu",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    text = "${plan.plannedCount}/${plan.stops.size} điểm bán",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(
                                text = formatMoney(plan.total),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        PrimaryButton(
                            // Disabled when nothing changed, which is also how the
                            // rep can tell their last save went through.
                            text = if (plan.canSave) "Lưu chỉ tiêu" else "Đã lưu",
                            onClick = onSave,
                            enabled = plan.canSave,
                            loading = state.saving,
                        )
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                state.loading -> LoadingBox()
                state.error != null && plan.stops.isEmpty() ->
                    ErrorBox(state.error, onRetry = onRetry)

                plan.stops.isEmpty() ->
                    ErrorBox("Không có khách hàng nào trong tuyến hôm nay")

                else -> LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    state.error?.let { message -> item { Notice(message, error = true) } }
                    if (state.saved) {
                        item { Notice("Đã lưu chỉ tiêu cho hôm nay.", error = false) }
                    }

                    items(plan.stops, key = { it.customerId }) { stop ->
                        StopCard(
                            stop = stop,
                            amount = plan.amountFor(stop),
                            suggestion = plan.suggestionFor(stop),
                            onAmountChanged = { onAmountChanged(stop.customerId, it) },
                            onUseSuggestion = { onUseSuggestion(stop) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StopCard(
    stop: DailyTargetStop,
    amount: Long,
    suggestion: Long?,
    onAmountChanged: (Long) -> Unit,
    onUseSuggestion: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    text = "${stop.visitOrder}.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stop.customerName,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = listOfNotNull(stop.customerCode, stop.address)
                            .joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // Named precisely: sales_order is scoped to the rep, so this is what
            // *you* last sold here, not what the outlet buys. Calling it "kỳ
            // trước" would imply a fuller picture than RLS can give.
            Text(
                text = stop.lastAmount
                    ?.let {
                        "Lần gần nhất bạn bán: ${formatMoney(it)}" +
                            (stop.lastDate?.let { d -> " · ${DateFormat.format(d)}" } ?: "")
                    }
                    ?: "Bạn chưa bán tại điểm này lần nào",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(8.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = if (amount == 0L) "" else amount.toString(),
                    onValueChange = { text ->
                        onAmountChanged(text.filter { it.isDigit() }.toLongOrNull() ?: 0L)
                    },
                    label = { Text("Chỉ tiêu (VND)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
                if (suggestion != null) {
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = onUseSuggestion) { Text("Dùng lần trước") }
                }
            }
        }
    }
}

@Composable
private fun Notice(message: String, error: Boolean) {
    Surface(
        color = if (error) {
            MaterialTheme.colorScheme.errorContainer
        } else {
            MaterialTheme.colorScheme.primaryContainer
        },
        contentColor = if (error) {
            MaterialTheme.colorScheme.onErrorContainer
        } else {
            MaterialTheme.colorScheme.onPrimaryContainer
        },
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

private val DateFormat = DateTimeFormatter.ofPattern("dd/MM/yyyy")

private val MoneyFormat = DecimalFormat(
    "#,##0",
    DecimalFormatSymbols(Locale.US).apply { groupingSeparator = '.' },
)

private fun formatMoney(value: Long): String = MoneyFormat.format(value)

// -----------------------------------------------------------------------------
// Previews
// -----------------------------------------------------------------------------

private val SampleStops = listOf(
    DailyTargetStop(
        customerId = "c1",
        customerCode = "KH001",
        customerName = "Tạp hoá Minh Anh",
        address = "45 Nguyễn Trãi",
        visitOrder = 1,
        target = 1_500_000,
        hasTarget = true,
        lastAmount = 1_240_000,
        lastDate = LocalDate.of(2026, 8, 11),
    ),
    DailyTargetStop(
        customerId = "c2",
        customerCode = "KH002",
        customerName = "Tạp hoá Bà Bảy",
        address = "112 Yersin",
        visitOrder = 2,
        target = 0,
        hasTarget = false,
        lastAmount = null,
        lastDate = null,
    ),
)

@Preview(name = "Chỉ tiêu ngày", showBackground = true, heightDp = 800)
@Composable
private fun DailyTargetPreview() {
    MyeSalesTheme {
        DailyTargetContent(
            state = DailyTargetUiState(
                loading = false,
                plan = DailyTargetPlan(
                    date = LocalDate.of(2026, 8, 18),
                    stops = SampleStops,
                ),
            ),
            onBack = {},
            onRetry = {},
            onAmountChanged = { _, _ -> },
            onUseSuggestion = {},
            onSave = {},
        )
    }
}
