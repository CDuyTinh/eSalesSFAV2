package com.tinhcd.myesalessfa.feature.reports

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.tinhcd.myesalessfa.domain.model.ActivityReport
import com.tinhcd.myesalessfa.domain.model.ActivityRow
import com.tinhcd.myesalessfa.domain.model.ActivitySummary
import com.tinhcd.myesalessfa.domain.model.CustomerSales
import com.tinhcd.myesalessfa.domain.model.ProductSales
import com.tinhcd.myesalessfa.domain.model.SalesReport
import com.tinhcd.myesalessfa.domain.model.VisitStatus
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The two reports a rep is asked about, on one screen.
 *
 * Together rather than behind a menu of two items, because the question that
 * brings a rep here is usually a comparison — the month is short, so what did I
 * do today — and a menu would put a tap between the two halves of it.
 */
@Composable
fun ReportsScreen(
    onBack: () -> Unit,
    viewModel: ReportsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    ReportsContent(
        state = state,
        onBack = onBack,
        onTabSelected = viewModel::onTabSelected,
        onCutSelected = viewModel::onCutSelected,
        onDateChanged = viewModel::onDateChanged,
        onMonthChanged = viewModel::onMonthChanged,
        onRefresh = viewModel::refresh,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReportsContent(
    state: ReportsUiState,
    onBack: () -> Unit,
    onTabSelected: (ReportTab) -> Unit,
    onCutSelected: (SalesCut) -> Unit,
    onDateChanged: (LocalDate) -> Unit,
    onMonthChanged: (LocalDate) -> Unit,
    onRefresh: () -> Unit,
) {
    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("Báo cáo") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Quay lại",
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = onRefresh, enabled = !state.loading) {
                            Icon(Icons.Default.Refresh, contentDescription = "Tải lại")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.brand.header,
                        titleContentColor = MaterialTheme.brand.onHeader,
                        navigationIconContentColor = MaterialTheme.brand.onHeader,
                        actionIconContentColor = MaterialTheme.brand.onHeader,
                    ),
                )
                TabRow(selectedTabIndex = state.tab.ordinal) {
                    ReportTab.entries.forEach { tab ->
                        Tab(
                            selected = tab == state.tab,
                            onClick = { onTabSelected(tab) },
                            text = { Text(tab.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (state.tab) {
                ReportTab.ACTIVITIES -> ActivitiesTab(
                    state = state,
                    onDateChanged = onDateChanged,
                )

                ReportTab.SALES -> SalesTab(
                    state = state,
                    onMonthChanged = onMonthChanged,
                    onCutSelected = onCutSelected,
                )
            }

            // Over the top of whatever is already there, rather than replacing it:
            // stepping a day back should not blank the screen it is comparing to.
            if (state.loading) {
                LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
            }
        }
    }
}

// -----------------------------------------------------------------------------
// Activities
// -----------------------------------------------------------------------------

@Composable
private fun ActivitiesTab(state: ReportsUiState, onDateChanged: (LocalDate) -> Unit) {
    val report = state.activities

    Column(Modifier.fillMaxSize()) {
        PeriodStepper(
            label = DateFormat.format(state.date),
            onPrevious = { onDateChanged(state.date.minusDays(1)) },
            // A report about tomorrow is not a report; it is an empty page that
            // looks like a bad day.
            onNext = { onDateChanged(state.date.plusDays(1)) },
            nextEnabled = state.date.isBefore(LocalDate.now()),
        )

        when {
            report == null && state.error != null -> ErrorBox(state.error)
            report == null -> LoadingBox()
            else -> LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item { ActivitySummaryCard(report.summary) }

                if (report.rows.isEmpty()) {
                    item {
                        Text(
                            text = "Không có cuộc viếng thăm nào trong ngày này",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                        )
                    }
                }

                items(report.rows, key = { it.visitId }) { row -> ActivityRowCard(row) }
            }
        }
    }
}

@Composable
private fun ActivitySummaryCard(summary: ActivitySummary) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom,
            ) {
                Text(
                    text = "Doanh số trong ngày",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = formatMoney(summary.orderAmount),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
            }

            HorizontalDivider()

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Metric(
                    label = "Ghé / Kế hoạch",
                    value = "${summary.visited}/${summary.planned}",
                    percent = summary.coverage,
                    modifier = Modifier.weight(1f),
                )
                Metric(
                    label = "Có đơn",
                    value = summary.strike.toString(),
                    percent = summary.strikeRate,
                    modifier = Modifier.weight(1f),
                )
                Metric(
                    label = "Không đơn",
                    value = summary.nonStrike.toString(),
                    percent = null,
                    modifier = Modifier.weight(1f),
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Metric(
                    label = "Đóng cửa",
                    value = summary.closed.toString(),
                    percent = null,
                    modifier = Modifier.weight(1f),
                )
                Metric(
                    label = "Ngoài tuyến",
                    value = summary.unplanned.toString(),
                    percent = null,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.weight(1f))
            }
        }
    }
}

/**
 * A figure with its share underneath, where a share means anything.
 *
 * The percentage is omitted rather than shown as 0% when there was nothing to be
 * a share of — a rep with no MCP stops today has not achieved 0% of anything.
 */
@Composable
private fun Metric(
    label: String,
    value: String,
    percent: Float?,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        if (percent != null) {
            Text(
                text = "${(percent * 100).toInt()}%",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun ActivityRowCard(row: ActivityRow) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = row.customerName,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = listOfNotNull(row.customerCode, row.address).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (!row.planned) {
                    Surface(
                        color = UnplannedViolet,
                        contentColor = Color.White,
                        shape = CircleShape,
                    ) {
                        Text(
                            text = "Ngoài tuyến",
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = row.clock(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = if (row.orderAmount > 0) formatMoney(row.orderAmount) else "Không đơn",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (row.orderAmount > 0) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

// -----------------------------------------------------------------------------
// Sales
// -----------------------------------------------------------------------------

@Composable
private fun SalesTab(
    state: ReportsUiState,
    onMonthChanged: (LocalDate) -> Unit,
    onCutSelected: (SalesCut) -> Unit,
) {
    val report = state.sales

    Column(Modifier.fillMaxSize()) {
        PeriodStepper(
            label = MonthFormat.format(state.month),
            onPrevious = { onMonthChanged(state.month.minusMonths(1)) },
            onNext = { onMonthChanged(state.month.plusMonths(1)) },
            nextEnabled = state.month.isBefore(LocalDate.now().withDayOfMonth(1)),
        )

        when {
            report == null && state.error != null -> ErrorBox(state.error)
            report == null -> LoadingBox()
            else -> LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item { SalesTotalCard(report) }
                item { CutSelector(selected = state.cut, onSelect = onCutSelected) }

                when (state.cut) {
                    SalesCut.CUSTOMER -> {
                        if (report.customers.isEmpty()) item { EmptyCut() }
                        items(report.customers, key = { it.customerCode }) { row ->
                            AmountRow(
                                title = row.customerName,
                                subtitle = "${row.customerCode} · ${row.orders} đơn",
                                amount = row.revenue,
                                share = report.revenue,
                            )
                        }
                    }

                    SalesCut.PRODUCT -> {
                        if (report.products.isEmpty()) item { EmptyCut() }
                        items(report.products, key = { it.productCode }) { row ->
                            AmountRow(
                                title = row.productName,
                                subtitle = "${row.productCode} · ${row.baseQty} ${row.baseUom}",
                                amount = row.revenue,
                                share = report.revenue,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SalesTotalCard(report: SalesReport) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = "Doanh thu (VND)",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = formatMoney(report.revenue),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "${report.orderCount} đơn hàng",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            val target = report.target
            val gap = report.gap
            Text(
                text = when {
                    target == null -> "Chưa được giao chỉ tiêu tháng này"
                    gap != null && gap > 0 ->
                        "Chỉ tiêu ${formatMoney(target)} · còn ${formatMoney(gap)}"

                    else ->
                        "Chỉ tiêu ${formatMoney(target)} · vượt ${formatMoney(-(gap ?: 0))}"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

@Composable
private fun CutSelector(selected: SalesCut, onSelect: (SalesCut) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SalesCut.entries.forEach { cut ->
            val active = cut == selected
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
                modifier = Modifier.clickable { onSelect(cut) },
            ) {
                Text(
                    text = cut.label,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }
    }
}

/**
 * A line of the breakdown, with a bar showing its share of the month.
 *
 * The bar is what makes the list readable at a glance: sorted by revenue, the
 * question is never the exact figure but whether the top line is most of the
 * month or merely the first of many.
 */
@Composable
private fun AmountRow(title: String, subtitle: String, amount: Long, share: Long) {
    Column {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = formatMoney(amount),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }

        val fraction = if (share > 0) (amount.toFloat() / share).coerceIn(0f, 1f) else 0f
        Box(
            Modifier
                .padding(top = 6.dp)
                .fillMaxWidth()
                .height(4.dp)
                .background(
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                    CircleShape,
                ),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(fraction)
                    .height(4.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape),
            )
        }
    }
}

@Composable
private fun EmptyCut() {
    Text(
        text = "Chưa có đơn hàng nào trong tháng này",
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
    )
}

// -----------------------------------------------------------------------------
// Shared
// -----------------------------------------------------------------------------

/** Back one period, forward one, and never past the period we are living in. */
@Composable
private fun PeriodStepper(
    label: String,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    nextEnabled: Boolean,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onPrevious) {
                Icon(Icons.Default.ChevronLeft, contentDescription = "Kỳ trước")
            }
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onNext, enabled = nextEnabled) {
                Icon(
                    Icons.Default.ChevronRight,
                    contentDescription = "Kỳ sau",
                    modifier = Modifier.size(24.dp),
                )
            }
        }
    }
}

private val UnplannedViolet = Color(0xFF5C00D4)

private val DateFormat = DateTimeFormatter.ofPattern("dd/MM/yyyy")

private val MonthFormat = DateTimeFormatter.ofPattern("'Tháng' MM/yyyy")

private val ClockFormat = DateTimeFormatter.ofPattern("HH:mm")

private val MoneyFormat = DecimalFormat(
    "#,##0",
    DecimalFormatSymbols(Locale.US).apply { groupingSeparator = '.' },
)

private fun formatMoney(value: Long): String = MoneyFormat.format(value)

private fun clockOf(epochMs: Long): String = ClockFormat
    .format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))

/**
 * The times, and how long it took. A visit still open says so rather than
 * showing a duration that would keep growing while nobody is in the shop.
 */
private fun ActivityRow.clock(): String {
    val start = checkInAtEpochMs ?: return status.label()
    val end = checkOutAtEpochMs ?: return "${clockOf(start)} · đang mở"
    return "${clockOf(start)} - ${clockOf(end)}" + (minutes?.let { " · $it phút" } ?: "")
}

private fun VisitStatus.label(): String = when (this) {
    VisitStatus.PLANNED -> "Chưa ghé"
    VisitStatus.IN_PROGRESS -> "Đang viếng thăm"
    VisitStatus.COMPLETED -> "Đã hoàn thành"
    VisitStatus.NO_ORDER -> "Không đặt hàng"
    VisitStatus.CLOSED -> "Đóng cửa"
    VisitStatus.ABANDONED -> "Bỏ dở - không check-out"
}

// -----------------------------------------------------------------------------
// Previews
// -----------------------------------------------------------------------------

private val SampleActivities = ActivityReport(
    date = LocalDate.of(2026, 8, 17),
    summary = ActivitySummary(
        planned = 12,
        visited = 9,
        unplanned = 1,
        strike = 6,
        nonStrike = 3,
        closed = 1,
        orderAmount = 4_250_000,
    ),
    rows = listOf(
        ActivityRow(
            visitId = "v1",
            customerCode = "KH0012",
            customerName = "Tạp hoá Bà Bảy",
            address = "12 Nguyễn Văn Cừ",
            planned = true,
            status = VisitStatus.COMPLETED,
            checkInAtEpochMs = 1_755_400_000_000,
            checkOutAtEpochMs = 1_755_401_680_000,
            minutes = 28,
            orderAmount = 1_450_000,
        ),
        ActivityRow(
            visitId = "v2",
            customerCode = "NEW-BR01-0001",
            customerName = "Quán Cô Tư",
            address = "45 Lý Thường Kiệt",
            planned = false,
            status = VisitStatus.NO_ORDER,
            checkInAtEpochMs = 1_755_403_000_000,
            checkOutAtEpochMs = 1_755_403_600_000,
            minutes = 10,
            orderAmount = 0,
        ),
    ),
)

private val SampleSales = SalesReport(
    month = LocalDate.of(2026, 8, 1),
    revenue = 68_400_000,
    orderCount = 42,
    target = 120_000_000,
    customers = listOf(
        CustomerSales("KH0012", "Tạp hoá Bà Bảy", 8, 24_000_000),
        CustomerSales("KH0013", "Cửa hàng Minh Anh", 5, 14_400_000),
    ),
    products = listOf(
        ProductSales("SP001", "Nước ngọt chai 500ml", "chai", 1_240, 30_000_000),
    ),
)

@Preview(name = "Báo cáo - hoạt động", showBackground = true, heightDp = 900)
@Composable
private fun ActivitiesPreview() {
    MyeSalesTheme {
        ReportsContent(
            state = ReportsUiState(
                tab = ReportTab.ACTIVITIES,
                date = LocalDate.of(2026, 8, 17),
                activities = SampleActivities,
            ),
            onBack = {},
            onTabSelected = {},
            onCutSelected = {},
            onDateChanged = {},
            onMonthChanged = {},
            onRefresh = {},
        )
    }
}

@Preview(name = "Báo cáo - doanh số", showBackground = true, heightDp = 900)
@Composable
private fun SalesPreview() {
    MyeSalesTheme {
        ReportsContent(
            state = ReportsUiState(
                tab = ReportTab.SALES,
                month = LocalDate.of(2026, 8, 1),
                sales = SampleSales,
            ),
            onBack = {},
            onTabSelected = {},
            onCutSelected = {},
            onDateChanged = {},
            onMonthChanged = {},
            onRefresh = {},
        )
    }
}
