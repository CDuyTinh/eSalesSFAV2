package com.tinhcd.myesalessfa.feature.reports

import androidx.compose.material.icons.filled.Event
import java.time.OffsetDateTime
import java.time.LocalDateTime
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.graphics.Shape
import androidx.compose.material.icons.filled.Work
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.foundation.border
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material.icons.filled.Workspaces
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.FilterList
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
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tinhcd.myesalessfa.core.ui.ErrorBox
import com.tinhcd.myesalessfa.core.ui.Kpi
import com.tinhcd.myesalessfa.core.ui.KpiBar
import com.tinhcd.myesalessfa.core.ui.PercentPill
import com.tinhcd.myesalessfa.core.ui.SkeletonList
import com.tinhcd.myesalessfa.core.ui.StatTile
import com.tinhcd.myesalessfa.core.ui.compactMoney
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
 * One report, named by the route that opened it.
 *
 * These six used to share a screen behind a scrolling tab row -- six labels do
 * not fit across a phone, so half were always off the edge and a rep had to
 * swipe the bar to learn what existed. The menu names them instead, which is
 * also what the legacy does.
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
    onCutSelected: (SalesCut) -> Unit,
    onDateChanged: (LocalDate) -> Unit,
    onMonthChanged: (LocalDate) -> Unit,
    onWindowChanged: (ReportWindow) -> Unit,
    onOrderStatus: (OrderReportStatus?) -> Unit,
    onSkuQuery: (String) -> Unit,
    onTradeKind: (TradeReportKind?) -> Unit,
    onRefresh: () -> Unit,
) {
    var filtering by rememberSaveable { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(state.kind.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            // The period is no longer on the page, so it is said here:
                            // a number without its dates is a number nobody can check.
                            Text(
                                periodLabel(state),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.brand.onHeader.copy(alpha = 0.85f),
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
                    actions = {
                        IconButton(onClick = { filtering = true }) {
                            Icon(Icons.Default.FilterList, contentDescription = "Bộ lọc")
                        }
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
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                // The toolbar's colour carried a little way down with rounded corners,
                // drawn behind the report so its first card sits over the edge — the
                // dashboard's band, so the two read as one app.
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(HeaderOverlap)
                        .background(
                            MaterialTheme.brand.header,
                            RoundedCornerShape(bottomStart = 20.dp, bottomEnd = 20.dp),
                        ),
                )
                when (state.kind) {
                    ReportKind.ACTIVITIES -> ActivitiesTab(
                        state = state,
                    )

                    ReportKind.SALES -> SalesTab(
                        state = state,
                        onCutSelected = onCutSelected,
                    )

                    ReportKind.ORDERS -> OrdersTab(
                        state = state,
                        onOrderStatus = onOrderStatus,
                    )

                    ReportKind.SKU -> SkuTab(
                        state = state,
                        onSkuQuery = onSkuQuery,
                    )

                    ReportKind.TRADE -> TradeTab(
                        state = state,
                        onTradeKind = onTradeKind,
                    )

                    ReportKind.WORK_RESULT -> WorkResultTab(
                        state = state,
                    )
                }

                // Over the top of whatever is already there, rather than replacing it:
                // changing the period should not blank the screen it is comparing to.
                if (state.loading) {
                    LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
                }
            }
        }

        AnimatedVisibility(
            visible = filtering,
            enter = slideInVertically { it },
            exit = slideOutVertically { it },
        ) {
            ReportFilterScreen(
                kind = state.kind,
                date = state.date,
                month = state.month,
                window = state.window,
                onDismiss = { filtering = false },
                onApplyDate = onDateChanged,
                onApplyMonth = onMonthChanged,
                onApplyWindow = onWindowChanged,
            )
        }
    }
}

/** How far the toolbar's colour runs under the first card. */
private val HeaderOverlap = 44.dp

/** The period the report on screen covers, as the toolbar states it. */
private fun periodLabel(state: ReportsUiState): String = when (state.kind) {
    ReportKind.ACTIVITIES -> "Ngày ${DateFormat.format(state.date)}"
    ReportKind.SALES -> MonthFormat.format(state.month)
    else -> "${DateFormat.format(state.window.fromDate)} - ${DateFormat.format(state.window.toDate)}"
}

// -----------------------------------------------------------------------------
// Activities
// -----------------------------------------------------------------------------

@Composable
private fun ActivitiesTab(state: ReportsUiState) {
    val report = state.activities

    Column(Modifier.fillMaxSize()) {
        when {
            report == null && state.error != null -> ErrorBox(state.error)
            report == null -> SkeletonList(count = 5)
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

/**
 * The day at a glance, in the same shape as the other reports: the money
 * first, then each figure in its own coloured block. A percentage sits under
 * a figure only where it is a share of something — a rep with no stops planned
 * today has not achieved 0% of anything.
 */
@Composable
private fun ActivitySummaryCard(summary: ActivitySummary) {
    SummaryCard {
        Text(
            "Doanh số trong ngày (VND)",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            formatMoney(summary.orderAmount),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )

        // One height per row: a tile with a share under it would otherwise stand taller.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.height(IntrinsicSize.Min)) {
            StatTile(
                label = "Ghé / KH",
                value = "${summary.visited}/${summary.planned}",
                colors = Kpi.Blue,
                footer = summary.coverage?.let { "${(it * 100).toInt()}%" },
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
            StatTile(
                label = "Có đơn",
                value = summary.strike.toString(),
                colors = Kpi.Green,
                footer = summary.strikeRate?.let { "${(it * 100).toInt()}%" },
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
            StatTile(
                label = "Không đơn",
                value = summary.nonStrike.toString(),
                colors = Kpi.Orange,
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.height(IntrinsicSize.Min)) {
            StatTile(
                label = "Đóng cửa",
                value = summary.closed.toString(),
                colors = Kpi.Slate,
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
            StatTile(
                label = "Ngoài tuyến",
                value = summary.unplanned.toString(),
                colors = Kpi.Violet,
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
            Spacer(Modifier.weight(1f))
        }
    }
}

/**
 * One visit, laid out as the legacy app's `daily_activities_report_item.dart`:
 * the outlet code in a tag coloured by outcome with the in and out times
 * opposite, the name, whether it was on the route, the address, then a rule and
 * the time spent and the order taken.
 */
@Composable
private fun ActivityRowCard(row: ActivityRow) {
    val closed = row.status == VisitStatus.CLOSED
    val tagColour = when {
        row.orderAmount > 0 -> ReportGreen
        closed -> ReportRed
        row.checkInAtEpochMs == null -> ReportGrey
        else -> ReportOrange
    }

    ReportItemCard {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SolidTag(row.customerCode, tagColour)
                val checkIn = row.checkInAtEpochMs
                when {
                    closed -> Text(
                        "Đã đóng cửa",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    checkIn != null -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TimeInOut(clockOf(checkIn), checkIn = true)
                        row.checkOutAtEpochMs?.let { TimeInOut(clockOf(it), checkIn = false) }
                    }
                }
            }
            Text(
                row.customerName,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(top = 10.dp),
            )
            InfoLine(Icons.Default.Route, if (row.planned) "Trong tuyến" else "Ngoài tuyến")
            row.address?.takeIf { it.isNotBlank() }?.let { InfoLine(Icons.Default.LocationOn, it) }
        }

        // A closed shop with no order has nothing below the rule, so no rule.
        if (!(closed && row.orderAmount == 0L)) {
            HorizontalDivider(thickness = 2.dp, color = MaterialTheme.colorScheme.background)
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f)) {
                    if (!closed) InfoLine(Icons.Default.Schedule, row.stayLabel(), topPadding = 0.dp)
                }
                if (row.orderAmount > 0) {
                    Text(
                        formatMoney(row.orderAmount),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                        color = ReportGreen,
                    )
                }
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
    onCutSelected: (SalesCut) -> Unit,
) {
    val report = state.sales

    Column(Modifier.fillMaxSize()) {
        when {
            report == null && state.error != null -> ErrorBox(state.error)
            report == null -> SkeletonList(count = 5)
            else -> LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item { SalesTotalCard(report) }
                item { CutSelector(selected = state.cut, onSelect = onCutSelected) }

                when (state.cut) {
                    SalesCut.CUSTOMER -> {
                        if (report.customers.isEmpty()) item { EmptyCut() }
                        items(report.customers, key = { it.customerCode }) { row -> CustomerSalesCard(row) }
                    }

                    SalesCut.PRODUCT -> {
                        if (report.products.isEmpty()) item { EmptyCut() }
                        items(report.products, key = { it.productCode }) { row -> ProductSalesCard(row) }
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
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
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
 * A customer's month, as the legacy MTD tab lists it: the name and the figure
 * in bold, the small print below.
 */
@Composable
private fun CustomerSalesCard(row: CustomerSales) {
    ReportItemCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    row.customerName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    formatMoney(row.revenue),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = ReportBlue,
                )
            }
            Row {
                Text(
                    row.customerCode,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Text("${row.orders} đơn hàng", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/**
 * A product's month, after the legacy `_itemProduct`: a picture square, the
 * name in bold, code and unit, then quantity and amount. There are no product
 * pictures here yet, so the square holds a box.
 */
@Composable
private fun ProductSalesCard(row: ProductSales) {
    ReportItemCard {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            PicturePlaceholder(Icons.Default.Inventory2, size = 64.dp, shape = RoundedCornerShape(10.dp))
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(row.productName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    buildAnnotatedString {
                        append(row.productCode)
                        withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant)) {
                            append(" | ${row.baseUom}")
                        }
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row {
                    Text(
                        "${row.baseQty} ${row.baseUom}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        formatMoney(row.revenue),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
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

private val DateFormat = DateTimeFormatter.ofPattern("dd/MM/yyyy")

private val MonthFormat = DateTimeFormatter.ofPattern("'Tháng' MM/yyyy")

private val ClockFormat = DateTimeFormatter.ofPattern("HH:mm")

private val MoneyFormat = DecimalFormat(
    "#,##0",
    DecimalFormatSymbols(Locale.US).apply { groupingSeparator = '.' },
)

private fun formatMoney(value: Long): String = MoneyFormat.format(value)

// -----------------------------------------------------------------------------
// The legacy item card's pieces
// -----------------------------------------------------------------------------

/** The legacy item colours (`#04A489`, `#F5A202`, `#D92332`, `#A4A4A8`, `#2D2DFE`). */
private val ReportGreen = Color(0xFF04A489)
private val ReportOrange = Color(0xFFF5A202)
private val ReportRed = Color(0xFFD92332)
private val ReportGrey = Color(0xFFA4A4A8)
private val ReportBlue = Color(0xFF2D2DFE)

/** The legacy item: white, 12dp corners, a soft shadow. */
@Composable
private fun ReportItemCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        content = content,
    )
}

/** A filled, rounded tag with white text — the legacy status tag. */
@Composable
private fun SolidTag(text: String, colour: Color) {
    Surface(color = colour, contentColor = Color.White, shape = RoundedCornerShape(12.dp)) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

/** A small grey icon and a line of grey text. */
@Composable
private fun InfoLine(icon: ImageVector, text: String, topPadding: Dp = 6.dp) {
    Row(Modifier.padding(top = topPadding), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(15.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A check-in (blue) or check-out (red) time, with its arrow. */
@Composable
private fun TimeInOut(time: String, checkIn: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (checkIn) Icons.AutoMirrored.Filled.Login else Icons.AutoMirrored.Filled.Logout,
            contentDescription = if (checkIn) "Vào" else "Ra",
            modifier = Modifier.size(15.dp),
            tint = if (checkIn) ReportBlue else ReportRed,
        )
        Spacer(Modifier.width(6.dp))
        Text(time, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Where the legacy card has a picture this data does not: a tinted shape with an icon. */
@Composable
private fun PicturePlaceholder(icon: ImageVector, size: Dp, shape: Shape) {
    Box(
        Modifier
            .size(size)
            .background(MaterialTheme.colorScheme.surfaceVariant, shape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(size * 0.5f),
            tint = MaterialTheme.colorScheme.outline,
        )
    }
}

/**
 * An outlet's picture stand-in: the first letters of its last two words, since
 * the first ones are the trade ("Tạp hoá", "Siêu thị") and would make every
 * shop "TH" or "ST".
 */
@Composable
private fun InitialsAvatar(name: String, size: Dp) {
    val initials = name.split(' ').filter { it.isNotBlank() }.takeLast(2)
        .joinToString("") { it.first().uppercase() }
    Box(
        Modifier
            .size(size)
            .background(MaterialTheme.brand.header.copy(alpha = 0.12f), CircleShape)
            .border(1.dp, MaterialTheme.brand.header.copy(alpha = 0.3f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            initials,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.brand.header,
        )
    }
}

/**
 * A server date or timestamp as dd/MM/yyyy, whichever shape it came in.
 * Anything unreadable is shown as it came rather than dropped.
 */
private fun formatReportDate(raw: String): String =
    parseReportInstant(raw)?.toLocalDate()?.format(DateFormat) ?: raw

/** "28/10/2026, 08:00 - 11:30" — the legacy card's working-time line. */
private fun workWhen(fromAt: String, toAt: String): String {
    val from = parseReportInstant(fromAt) ?: return fromAt
    val to = parseReportInstant(toAt)
    val day = from.toLocalDate().format(DateFormat)
    val start = from.toLocalTime().format(ClockFormat)
    return if (to == null) "$day, $start" else "$day, $start - ${to.toLocalTime().format(ClockFormat)}"
}

/** Timestamps with an offset in the phone's zone; bare dates at midnight. */
private fun parseReportInstant(raw: String): LocalDateTime? {
    val text = raw.trim().replace(' ', 'T')
    // Postgres writes "+07" where ISO wants "+07:00".
    val iso = text.replace(Regex("([+-]\\d{2})$"), "$1:00")
    return runCatching { OffsetDateTime.parse(iso).atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime() }
        .recoverCatching { LocalDateTime.parse(text) }
        .recoverCatching { LocalDate.parse(text.take(10)).atStartOfDay() }
        .getOrNull()
}

/** The time in the shop, as the card's lower line says it. */
private fun ActivityRow.stayLabel(): String = when {
    minutes != null -> "$minutes phút"
    checkInAtEpochMs != null && checkOutAtEpochMs == null -> "Đang viếng thăm"
    else -> status.label()
}


// -----------------------------------------------------------------------------
// The four window-based reports
//
// All four open the same way: a coloured summary of the period, then the rows
// behind it. The tiles are the dashboard's own, so a rep moving between the
// Overview tab and a report is reading one language rather than two.
// -----------------------------------------------------------------------------

/** The card every report's summary sits in. One shape, four contents. */
@Composable
private fun SummaryCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

/** The card every report's rows sit in. */
@Composable
private fun RowCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            content = content,
        )
    }
}

/**
 * The filter row.
 *
 * "Tất cả" first and always, then only the values that occur — a chip reading
 * zero is a button that does nothing, and the legacy's fixed chip rows had
 * exactly that.
 */
@Composable
private fun <T> ChipRow(
    allLabel: String,
    values: List<T>,
    selected: T?,
    label: (T) -> String,
    onSelect: (T?) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = selected == null,
            onClick = { onSelect(null) },
            label = { Text(allLabel) },
        )
        values.forEach { value ->
            FilterChip(
                selected = selected == value,
                onClick = { onSelect(value) },
                label = { Text(label(value)) },
            )
        }
    }
}

@Composable
private fun ReportEmpty(icon: ImageVector, text: String) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp, horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(40.dp),
            tint = MaterialTheme.colorScheme.outlineVariant,
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** A label and a value on one line, for the small print under a headline. */
@Composable
private fun DetailLine(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}

// -----------------------------------------------------------------------------
// Báo cáo đơn hàng
// -----------------------------------------------------------------------------

@Composable
private fun OrdersTab(
    state: ReportsUiState,
    onOrderStatus: (OrderReportStatus?) -> Unit,
) {
    val report = state.orders

    Column(Modifier.fillMaxSize()) {
        if (report == null) {
            ReportEmpty(Icons.Default.ReceiptLong, state.error ?: "Đang tải báo cáo đơn hàng")
            return@Column
        }

        val rows = report.items.filter {
            state.orderStatus == null || it.status == state.orderStatus
        }
        val average = if (report.total > 0) report.totalAmount / report.total else 0L

        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                SummaryCard {
                    Text(
                        "Doanh số kỳ này (VND)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        // Cancelled orders are in the count but not the money.
                        formatMoney(report.totalAmount),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatTile(
                            label = "Số đơn",
                            value = report.total.toString(),
                            colors = Kpi.Green,
                            modifier = Modifier.weight(1f),
                        )
                        StatTile(
                            label = "Đã xác nhận",
                            value = report.count(OrderReportStatus.CONFIRMED).toString(),
                            colors = Kpi.Blue,
                            modifier = Modifier.weight(1f),
                        )
                        StatTile(
                            label = "TB / đơn",
                            value = compactMoney(average),
                            colors = Kpi.Violet,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            if (report.presentStatuses.isNotEmpty()) {
                item {
                    ChipRow(
                        allLabel = "Tất cả ${report.total}",
                        values = report.presentStatuses,
                        selected = state.orderStatus,
                        label = { "${it.label} ${report.count(it)}" },
                        onSelect = onOrderStatus,
                    )
                }
            }

            if (rows.isEmpty()) {
                item { ReportEmpty(Icons.Default.ReceiptLong, "Kỳ này chưa có đơn hàng") }
            } else {
                items(rows, key = { it.id }) { row -> OrderReportCard(row) }
            }
        }
    }
}

/**
 * An order, after the legacy `_buildReportOrderItem`: status tag and date,
 * the order number against the goods value, the customer against the amount
 * owed, the line count, and whether it was taken on the route.
 */
@Composable
private fun OrderReportCard(row: OrderReportRow) {
    ReportItemCard {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SolidTag(row.status.label, orderColour(row.status))
                Text(
                    formatReportDate(row.orderDate),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    row.orderNo,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                )
                Text(formatMoney(row.subTotal), style = MaterialTheme.typography.bodyLarge)
            }
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    row.customerName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    formatMoney(row.totalAmount),
                    style = MaterialTheme.typography.bodyMedium,
                    color = ReportGreen,
                    modifier = Modifier.padding(start = 14.dp),
                )
            }
            Row {
                Text(
                    "Số dòng hàng",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Text(row.lineCount.toString(), style = MaterialTheme.typography.bodyMedium)
            }
            InfoLine(
                Icons.Default.ShoppingCart,
                if (row.inRoute) "Đơn trong tuyến" else "Đơn ngoài tuyến",
                topPadding = 0.dp,
            )
        }
    }
}

private fun orderColour(status: OrderReportStatus): Color = when (status) {
    OrderReportStatus.NEW -> Color(0xFFF5A202)
    OrderReportStatus.CONFIRMED -> Color(0xFF04A489)
    OrderReportStatus.CANCELLED -> Color(0xFFC62828)
}

// -----------------------------------------------------------------------------
// Báo cáo SKU
// -----------------------------------------------------------------------------

@Composable
private fun SkuTab(
    state: ReportsUiState,
    onSkuQuery: (String) -> Unit,
) {
    val report = state.sku

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = state.skuQuery,
            onValueChange = onSkuQuery,
            placeholder = { Text("Tìm theo tên hoặc mã sản phẩm") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            // White and label-less: it sits on the toolbar band, where a floating
            // label would land on the blue.
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surface,
                unfocusedContainerColor = MaterialTheme.colorScheme.surface,
            ),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )

        if (report == null) {
            ReportEmpty(Icons.Default.Inventory2, state.error ?: "Đang tải báo cáo SKU")
            return@Column
        }

        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                SummaryCard {
                    Text(
                        "Thành tiền kỳ này (VND)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        formatMoney(report.amountTotal),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatTile(
                            label = "Số SKU",
                            value = report.skuCount.toString(),
                            colors = Kpi.Blue,
                            modifier = Modifier.weight(1f),
                        )
                        StatTile(
                            label = "Đã xác nhận",
                            value = report.qtyConfirmed.toString(),
                            colors = Kpi.Green,
                            modifier = Modifier.weight(1f),
                        )
                        StatTile(
                            label = "Chờ xử lý",
                            value = report.qtyPending.toString(),
                            colors = Kpi.Orange,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            if (report.items.isEmpty()) {
                item {
                    ReportEmpty(
                        Icons.Default.Inventory2,
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

/**
 * A product's quantity, after the legacy SKU list: a round picture, the name
 * against the quantity and unit, the code against what has been confirmed.
 */
@Composable
private fun SkuReportCard(row: SkuReportRow) {
    ReportItemCard {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PicturePlaceholder(Icons.Default.Inventory2, size = 40.dp, shape = CircleShape)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.Top) {
                    Text(row.productName, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    Text("${row.qtyTotal} ${row.stockUom}", style = MaterialTheme.typography.bodyLarge)
                }
                Row(verticalAlignment = Alignment.Top) {
                    Text(
                        row.productCode,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        buildAnnotatedString {
                            withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant)) {
                                append("Đã xác nhận: ")
                            }
                            withStyle(SpanStyle(color = ReportGreen)) { append(row.qtyConfirmed.toString()) }
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
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
    onTradeKind: (TradeReportKind?) -> Unit,
) {
    val report = state.trade

    Column(Modifier.fillMaxSize()) {
        if (report == null) {
            ReportEmpty(Icons.Default.Workspaces, state.error ?: "Đang tải báo cáo thương mại")
            return@Column
        }

        val rows = report.of(state.tradeKind)

        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                SummaryCard {
                    Text(
                        "Lượt đăng ký chương trình",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        report.total.toString(),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatTile(
                            label = "Trưng bày",
                            value = report.displayCount.toString(),
                            colors = Kpi.Violet,
                            modifier = Modifier.weight(1f),
                        )
                        StatTile(
                            label = "Tích lũy",
                            value = report.loyaltyCount.toString(),
                            colors = Kpi.Green,
                            modifier = Modifier.weight(1f),
                        )
                        StatTile(
                            // Outlets, not sign-ups: one shop in three
                            // programmes is one shop reached.
                            label = "Khách hàng",
                            value = report.customerCount.toString(),
                            colors = Kpi.Blue,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            if (report.total > 0) {
                item {
                    ChipRow(
                        allLabel = "Tất cả ${report.total}",
                        values = TradeReportKind.entries,
                        selected = state.tradeKind,
                        label = { it.label },
                        onSelect = onTradeKind,
                    )
                }
            }

            if (rows.isEmpty()) {
                item {
                    ReportEmpty(Icons.Default.Workspaces, "Kỳ này chưa đăng ký chương trình nào")
                }
            } else {
                items(rows, key = { it.kind.wire + it.customerCode + it.programCode }) { row ->
                    TradeReportCard(row)
                }
            }
        }
    }
}

/**
 * A sign-up, after the legacy trade list: the outlet's picture (its initials,
 * here), the programme in bold, and below it who signed and how.
 */
@Composable
private fun TradeReportCard(row: TradeReportRow) {
    ReportItemCard {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            InitialsAvatar(row.customerName, size = 48.dp)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(row.programName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    "${row.customerCode} · ${row.customerName}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SolidTag(row.kind.label, tradeColour(row.kind))
                    row.levelName?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                InfoLine(Icons.Default.Event, "Đăng ký ${formatReportDate(row.registeredAt)}", topPadding = 0.dp)
            }
        }
    }
}

private fun tradeColour(kind: TradeReportKind): Color = when (kind) {
    TradeReportKind.DISPLAY -> Color(0xFF5C00D4)
    TradeReportKind.LOYALTY -> Color(0xFF04A489)
}

// -----------------------------------------------------------------------------
// Báo cáo kết quả công việc
// -----------------------------------------------------------------------------

@Composable
private fun WorkResultTab(
    state: ReportsUiState,
) {
    val report = state.workResult

    Column(Modifier.fillMaxSize()) {
        if (report == null) {
            ReportEmpty(Icons.Default.TaskAlt, state.error ?: "Đang tải báo cáo kết quả công việc")
            return@Column
        }

        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                SummaryCard {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column {
                            Text(
                                "Kế hoạch đã kết thúc",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                report.total.toString(),
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                        report.completionRate?.let {
                            PercentPill(
                                percent = (it * 100).toInt(),
                                color = Kpi.Green.first(),
                            )
                        }
                    }

                    // Nothing finished is not nought per cent completed — it is
                    // no answer at all, so the bar stays away rather than
                    // drawing an accusation the data does not support.
                    report.completionRate?.let { KpiBar(progress = it, ramp = Kpi.Green) }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatTile(
                            label = "Hoàn thành",
                            value = report.completed.toString(),
                            colors = Kpi.Green,
                            modifier = Modifier.weight(1f),
                        )
                        StatTile(
                            label = "Đã hủy",
                            value = report.cancelled.toString(),
                            colors = Kpi.Slate,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            if (report.items.isEmpty()) {
                item { ReportEmpty(Icons.Default.TaskAlt, "Kỳ này chưa có kế hoạch nào kết thúc") }
            } else {
                items(report.items, key = { it.id }) { row -> WorkResultCard(row) }
            }
        }
    }
}

/**
 * A finished plan, after the legacy `work_result_report_item.dart`: its
 * status tag, what it was, when, and what kind of work.
 */
@Composable
private fun WorkResultCard(row: WorkResultRow) {
    val done = row.status == WorkPlanStatus.COMPLETED

    ReportItemCard {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            // The legacy colours: a completed plan is settled business, grey; a
            // cancelled one is what a reader is looking for, red.
            SolidTag(if (done) "Hoàn thành" else "Đã hủy", if (done) ReportGrey else ReportRed)
            Text(
                row.content,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(top = 10.dp),
            )
            InfoLine(Icons.Default.Schedule, workWhen(row.fromAt, row.toAt))
            InfoLine(Icons.Default.Work, row.categoryName)
            if (row.objectCount > 0) InfoLine(Icons.Default.Groups, "${row.objectCount} đối tượng")
            row.outcome?.takeIf { it.isNotBlank() }?.let { InfoLine(Icons.AutoMirrored.Filled.Notes, it) }
        }
    }
}

private fun clockOf(epochMs: Long): String = ClockFormat
    .format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))

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
                kind = ReportKind.ACTIVITIES,
                date = LocalDate.of(2026, 8, 17),
                activities = SampleActivities,
            ),
            onBack = {},
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
                kind = ReportKind.SALES,
                month = LocalDate.of(2026, 8, 1),
                sales = SampleSales,
            ),
            onBack = {},
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
