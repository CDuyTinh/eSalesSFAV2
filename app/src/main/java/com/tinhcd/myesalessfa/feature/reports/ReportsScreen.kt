package com.tinhcd.myesalessfa.feature.reports

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.ScrollableTabRow
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
import com.tinhcd.myesalessfa.domain.model.OrderReportRow
import com.tinhcd.myesalessfa.domain.model.OrderReportStatus
import com.tinhcd.myesalessfa.domain.model.ReportWindow
import com.tinhcd.myesalessfa.domain.model.SkuReportRow
import com.tinhcd.myesalessfa.domain.model.TradeReportKind
import com.tinhcd.myesalessfa.domain.model.TradeReportRow
import com.tinhcd.myesalessfa.domain.model.WorkPlanStatus
import com.tinhcd.myesalessfa.domain.model.WorkResultRow
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
        onWindowChanged = viewModel::onWindowChanged,
        onOrderStatus = viewModel::onOrderStatus,
        onSkuQuery = viewModel::onSkuQuery,
        onTradeKind = viewModel::onTradeKind,
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
    onWindowChanged: (ReportWindow) -> Unit,
    onOrderStatus: (OrderReportStatus?) -> Unit,
    onSkuQuery: (String) -> Unit,
    onTradeKind: (TradeReportKind?) -> Unit,
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
                // Scrollable now that there are six: a fixed TabRow would give
                // each label a sixth of the width and truncate every one.
                ScrollableTabRow(
                    selectedTabIndex = state.tab.ordinal,
                    edgePadding = 8.dp,
                ) {
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

                ReportTab.ORDERS -> OrdersTab(
                    state = state,
                    onWindowChanged = onWindowChanged,
                    onOrderStatus = onOrderStatus,
                )

                ReportTab.SKU -> SkuTab(
                    state = state,
                    onWindowChanged = onWindowChanged,
                    onSkuQuery = onSkuQuery,
                )

                ReportTab.TRADE -> TradeTab(
                    state = state,
                    onWindowChanged = onWindowChanged,
                    onTradeKind = onTradeKind,
                )

                ReportTab.WORK_RESULT -> WorkResultTab(
                    state = state,
                    onWindowChanged = onWindowChanged,
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

// -----------------------------------------------------------------------------
// The four window-based reports
//
// All four open with the same header: the range they cover, and a way to move
// it. They share one window because a rep asking "what did I do this month"
// wants the same month answered four ways.
// -----------------------------------------------------------------------------

/** Moves the shared range a whole month at a time, which is how a rep thinks. */
@Composable
private fun WindowStepper(window: ReportWindow, onWindowChanged: (ReportWindow) -> Unit) {
    val start = window.fromDate
    val label = "${start.format(DateFormat)} - ${window.toDate.format(DateFormat)}"

    PeriodStepper(
        label = label,
        onPrevious = {
            val previous = start.minusMonths(1)
            onWindowChanged(
                ReportWindow(
                    previous.withDayOfMonth(1),
                    previous.withDayOfMonth(previous.lengthOfMonth()),
                ),
            )
        },
        onNext = {
            val next = start.plusMonths(1)
            onWindowChanged(
                ReportWindow(
                    next.withDayOfMonth(1),
                    // Never past today: a range running into the future would
                    // show an empty tail the rep cannot do anything about.
                    minOf(next.withDayOfMonth(next.lengthOfMonth()), LocalDate.now()),
                ),
            )
        },
        // Stepping forward past this month would ask for a range that has not
        // happened.
        nextEnabled = start.withDayOfMonth(1) < LocalDate.now().withDayOfMonth(1),
    )
}

@Composable
private fun ReportEmpty(text: String) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

// -----------------------------------------------------------------------------
// Báo cáo đơn hàng
// -----------------------------------------------------------------------------

@Composable
private fun OrdersTab(
    state: ReportsUiState,
    onWindowChanged: (ReportWindow) -> Unit,
    onOrderStatus: (OrderReportStatus?) -> Unit,
) {
    val report = state.orders

    Column(Modifier.fillMaxSize()) {
        WindowStepper(state.window, onWindowChanged)

        if (report == null) {
            ReportEmpty(state.error ?: "Đang tải báo cáo đơn hàng")
            return@Column
        }

        val rows = report.items.filter {
            state.orderStatus == null || it.status == state.orderStatus
        }

        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                ) {
                    Row(Modifier.fillMaxWidth().padding(16.dp)) {
                        Metric(
                            label = "Số đơn",
                            value = report.total.toString(),
                            percent = null,
                            modifier = Modifier.weight(1f),
                        )
                        Metric(
                            // Cancelled orders are in the count but not the
                            // money, which is the honest pair to show together.
                            label = "Thành tiền (VND)",
                            value = formatMoney(report.totalAmount),
                            percent = null,
                            modifier = Modifier.weight(1.4f),
                        )
                    }
                }
            }

            if (report.presentStatuses.isNotEmpty()) {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FilterChip(
                            selected = state.orderStatus == null,
                            onClick = { onOrderStatus(null) },
                            label = { Text("Tất cả ${report.total}") },
                        )
                        report.presentStatuses.forEach { status ->
                            FilterChip(
                                selected = state.orderStatus == status,
                                onClick = { onOrderStatus(status) },
                                label = { Text("${status.label} ${report.count(status)}") },
                            )
                        }
                    }
                }
            }

            if (rows.isEmpty()) {
                item { ReportEmpty("Kỳ này chưa có đơn hàng") }
            } else {
                items(rows, key = { it.id }) { row -> OrderReportCard(row) }
            }
        }
    }
}

@Composable
private fun OrderReportCard(row: OrderReportRow) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = row.customerName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = formatMoney(row.totalAmount),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
            }

            Text(
                text = "${row.orderNo} · ${row.customerCode}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = row.status.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = when (row.status) {
                        OrderReportStatus.CONFIRMED -> MaterialTheme.colorScheme.primary
                        OrderReportStatus.CANCELLED -> MaterialTheme.colorScheme.error
                        OrderReportStatus.NEW -> MaterialTheme.colorScheme.tertiary
                    },
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = listOf(
                        row.orderDate,
                        "${row.lineCount} mặt hàng",
                        // Off-route is the interesting case, so only it is said.
                        if (row.inRoute) "" else "Ngoài tuyến",
                    ).filter { it.isNotBlank() }.joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}

// -----------------------------------------------------------------------------
// Báo cáo SKU
// -----------------------------------------------------------------------------

@Composable
private fun SkuTab(
    state: ReportsUiState,
    onWindowChanged: (ReportWindow) -> Unit,
    onSkuQuery: (String) -> Unit,
) {
    val report = state.sku

    Column(Modifier.fillMaxSize()) {
        WindowStepper(state.window, onWindowChanged)

        OutlinedTextField(
            value = state.skuQuery,
            onValueChange = onSkuQuery,
            label = { Text("Tìm theo tên hoặc mã sản phẩm") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )

        if (report == null) {
            ReportEmpty(state.error ?: "Đang tải báo cáo SKU")
            return@Column
        }

        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                ) {
                    Row(Modifier.fillMaxWidth().padding(16.dp)) {
                        Metric(
                            label = "Số SKU",
                            value = report.skuCount.toString(),
                            percent = null,
                            modifier = Modifier.weight(0.8f),
                        )
                        Metric(
                            label = "Đã xác nhận",
                            value = report.qtyConfirmed.toString(),
                            percent = null,
                            modifier = Modifier.weight(1f),
                        )
                        Metric(
                            label = "Chờ xử lý",
                            value = report.qtyPending.toString(),
                            percent = null,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            if (report.items.isEmpty()) {
                item {
                    ReportEmpty(
                        if (state.skuQuery.isNotBlank()) "Không tìm thấy sản phẩm nào"
                        else "Kỳ này chưa bán mặt hàng nào",
                    )
                }
            } else {
                items(report.items, key = { it.productId }) { row -> SkuReportCard(row) }
            }
        }
    }
}

@Composable
private fun SkuReportCard(row: SkuReportRow) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = row.productName,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${row.productCode} · ${row.stockUom}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Metric(
                    label = "Tổng",
                    value = row.qtyTotal.toString(),
                    percent = null,
                    modifier = Modifier.weight(1f),
                )
                Metric(
                    label = "Xác nhận",
                    value = row.qtyConfirmed.toString(),
                    percent = row.confirmedShare,
                    modifier = Modifier.weight(1f),
                )
                Metric(
                    label = "Thành tiền",
                    value = formatMoney(row.amountTotal),
                    percent = null,
                    modifier = Modifier.weight(1.4f),
                )
            }
        }
    }
}

// -----------------------------------------------------------------------------
// Báo cáo thương mại
// -----------------------------------------------------------------------------

@Composable
private fun TradeTab(
    state: ReportsUiState,
    onWindowChanged: (ReportWindow) -> Unit,
    onTradeKind: (TradeReportKind?) -> Unit,
) {
    val report = state.trade

    Column(Modifier.fillMaxSize()) {
        WindowStepper(state.window, onWindowChanged)

        if (report == null) {
            ReportEmpty(state.error ?: "Đang tải báo cáo thương mại")
            return@Column
        }

        val rows = report.of(state.tradeKind)

        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                ) {
                    Row(Modifier.fillMaxWidth().padding(16.dp)) {
                        Metric(
                            label = "Trưng bày",
                            value = report.displayCount.toString(),
                            percent = null,
                            modifier = Modifier.weight(1f),
                        )
                        Metric(
                            label = "Tích lũy",
                            value = report.loyaltyCount.toString(),
                            percent = null,
                            modifier = Modifier.weight(1f),
                        )
                        Metric(
                            // Outlets, not sign-ups: one shop in three
                            // programmes is one shop reached.
                            label = "Khách hàng",
                            value = report.customerCount.toString(),
                            percent = null,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            if (report.total > 0) {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FilterChip(
                            selected = state.tradeKind == null,
                            onClick = { onTradeKind(null) },
                            label = { Text("Tất cả ${report.total}") },
                        )
                        TradeReportKind.entries.forEach { kind ->
                            FilterChip(
                                selected = state.tradeKind == kind,
                                onClick = { onTradeKind(kind) },
                                label = { Text(kind.label) },
                            )
                        }
                    }
                }
            }

            if (rows.isEmpty()) {
                item { ReportEmpty("Kỳ này chưa đăng ký chương trình nào") }
            } else {
                items(rows, key = { it.kind.wire + it.customerCode + it.programCode }) { row ->
                    TradeReportCard(row)
                }
            }
        }
    }
}

@Composable
private fun TradeReportCard(row: TradeReportRow) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = row.customerName,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = row.programName,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = listOfNotNull(
                    row.kind.label,
                    row.levelName,
                    row.programCode,
                    row.registeredAt,
                ).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

// -----------------------------------------------------------------------------
// Báo cáo kết quả công việc
// -----------------------------------------------------------------------------

@Composable
private fun WorkResultTab(
    state: ReportsUiState,
    onWindowChanged: (ReportWindow) -> Unit,
) {
    val report = state.workResult

    Column(Modifier.fillMaxSize()) {
        WindowStepper(state.window, onWindowChanged)

        if (report == null) {
            ReportEmpty(state.error ?: "Đang tải báo cáo kết quả công việc")
            return@Column
        }

        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                ) {
                    Row(Modifier.fillMaxWidth().padding(16.dp)) {
                        Metric(
                            label = "Hoàn thành",
                            value = report.completed.toString(),
                            percent = report.completionRate,
                            modifier = Modifier.weight(1f),
                        )
                        Metric(
                            label = "Đã hủy",
                            value = report.cancelled.toString(),
                            percent = null,
                            modifier = Modifier.weight(1f),
                        )
                        Metric(
                            label = "Tổng",
                            value = report.total.toString(),
                            percent = null,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            if (report.items.isEmpty()) {
                item { ReportEmpty("Kỳ này chưa có kế hoạch nào kết thúc") }
            } else {
                items(report.items, key = { it.id }) { row -> WorkResultCard(row) }
            }
        }
    }
}

@Composable
private fun WorkResultCard(row: WorkResultRow) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = row.content,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = row.status.label,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (row.status == WorkPlanStatus.COMPLETED) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
            }

            Text(
                text = listOf(
                    row.categoryName,
                    row.fromAt.take(10),
                    if (row.objectCount > 0) "${row.objectCount} đối tượng" else "",
                ).filter { it.isNotBlank() }.joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )

            row.outcome?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

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
            onWindowChanged = {},
            onOrderStatus = {},
            onSkuQuery = {},
            onTradeKind = {},
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
            onWindowChanged = {},
            onOrderStatus = {},
            onSkuQuery = {},
            onTradeKind = {},
            onRefresh = {},
        )
    }
}
