package com.tinhcd.myesalessfa.feature.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tinhcd.myesalessfa.core.ui.theme.MyeSalesTheme
import com.tinhcd.myesalessfa.core.ui.theme.brand
import com.tinhcd.myesalessfa.domain.model.ChartRange
import com.tinhcd.myesalessfa.domain.model.DashboardOverview
import com.tinhcd.myesalessfa.domain.model.MonthFigures
import com.tinhcd.myesalessfa.domain.model.SalesPoint
import com.tinhcd.myesalessfa.domain.model.TodayFigures
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.LocalDate
import java.util.Locale

/**
 * The Overview tab.
 *
 * Three blocks, in the order the legacy screen had them and for the same reason:
 * today first, because that is what the rep can still change; the month next,
 * because that is what they are measured on; the trend last, because it is
 * context rather than a call to action.
 */
@Composable
fun DashboardScreen(
    onOpenDrawer: () -> Unit,
    viewModel: DashboardViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    DashboardContent(
        state = state,
        onOpenDrawer = onOpenDrawer,
        onRefresh = viewModel::load,
        onRangeSelected = viewModel::onRangeSelected,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DashboardContent(
    state: DashboardUiState,
    onOpenDrawer: () -> Unit,
    onRefresh: () -> Unit,
    onRangeSelected: (ChartRange) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Tổng quan") },
                navigationIcon = {
                    IconButton(onClick = onOpenDrawer) {
                        Icon(Icons.Default.Menu, contentDescription = "Mở menu")
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
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            val overview = state.overview
            when {
                // Only on the very first load. A refresh keeps the figures on
                // screen and replaces them when the new ones land.
                overview == null && state.loading ->
                    CircularProgressIndicator(Modifier.align(Alignment.Center))

                overview == null ->
                    EmptyState(
                        message = state.error ?: "Chưa có số liệu",
                        modifier = Modifier.align(Alignment.Center),
                    )

                else -> Figures(
                    overview = overview,
                    range = state.range,
                    error = state.error,
                    onRangeSelected = onRangeSelected,
                )
            }

            if (state.loading && overview != null) {
                LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
            }
        }
    }
}

@Composable
private fun Figures(
    overview: DashboardOverview,
    range: ChartRange,
    error: String?,
    onRangeSelected: (ChartRange) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (error != null) {
            item { ErrorNotice(error) }
        }
        item { TodayCard(overview.today) }
        item { SectionTitle("KPI tháng này") }
        item { MonthCard(overview.month) }
        item { SectionTitle("Doanh số bán hàng") }
        item {
            TrendCard(
                overview = overview,
                range = range,
                onRangeSelected = onRangeSelected,
            )
        }
    }
}

@Composable
private fun TodayCard(today: TodayFigures) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("Hôm nay", style = MaterialTheme.typography.titleLarge)
            Text(
                text = "Doanh thu (VND)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = formatMoney(today.revenue),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StatTile(
                    label = "Viếng thăm",
                    value = "${today.visitDone}/${today.visitPlanned}",
                    colors = VisitTile,
                    modifier = Modifier.weight(1f),
                )
                StatTile(
                    label = "Đơn hàng",
                    value = today.orderCount.toString(),
                    colors = OrderTile,
                    modifier = Modifier.weight(1f),
                )
                StatTile(
                    label = "SKU/đơn",
                    value = formatDecimal(today.skuPerOrder),
                    colors = SkuTile,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * Fixed rather than theme-derived, and light text on all three.
 *
 * These read as one row of three, so they have to stay distinguishable from each
 * other rather than from the background — which is what would happen if they
 * tracked the colour scheme and dark mode pulled them all toward the same value.
 */
private val VisitTile = listOf(Color(0xFF2D2DFE), Color(0xFF5757FE))
private val OrderTile = listOf(Color(0xFF04A489), Color(0xFF36B6A0))
private val SkuTile = listOf(Color(0xFFFF5A30), Color(0xFFFF7B59))

@Composable
private fun StatTile(
    label: String,
    value: String,
    colors: List<Color>,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .background(Brush.verticalGradient(colors), RoundedCornerShape(12.dp))
            .padding(vertical = 12.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.9f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = Color.White,
        )
    }
}

@Composable
private fun MonthCard(month: MonthFigures) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            KpiRow(
                label = "Doanh thu (VND)",
                actual = formatMoney(month.revenue),
                target = month.revenueTarget?.let { formatMoney(it) },
                progress = month.revenueProgress,
            )
            KpiRow(
                label = "Đơn hàng",
                actual = month.orderCount.toString(),
                target = month.orderTarget?.toString(),
                progress = month.orderProgress,
            )
        }
    }
}

/**
 * Says "no target set" in words rather than drawing an empty bar.
 *
 * An empty bar is a claim — that the rep has achieved none of what was asked —
 * and it is not one this screen is in a position to make when head office has
 * asked for nothing.
 */
@Composable
private fun KpiRow(
    label: String,
    actual: String,
    target: String?,
    progress: Float?,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = if (target != null) "$actual / $target" else actual,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
        if (progress != null) {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth().height(6.dp),
            )
        } else {
            Text(
                text = "Chưa được giao chỉ tiêu tháng này",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TrendCard(
    overview: DashboardOverview,
    range: ChartRange,
    onRangeSelected: (ChartRange) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            RangeTabs(selected = range, onSelected = onRangeSelected)

            Text(
                text = formatMoney(overview.total(range)),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )

            SalesTrendChart(
                points = overview.series(range),
                modifier = Modifier.fillMaxWidth().height(200.dp),
            )
        }
    }
}

private val RangeLabels = listOf(
    ChartRange.THIS_WEEK to "Tuần này",
    ChartRange.LAST_WEEK to "Tuần trước",
    ChartRange.THIS_MONTH to "Tháng này",
)

@Composable
private fun RangeTabs(
    selected: ChartRange,
    onSelected: (ChartRange) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RangeLabels.forEach { (range, label) ->
            val active = range == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .border(
                        width = 1.dp,
                        color = if (active) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.outline
                        },
                        shape = RoundedCornerShape(12.dp),
                    )
                    .background(
                        color = if (active) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            Color.Transparent
                        },
                        shape = RoundedCornerShape(12.dp),
                    )
                    .clickable { onSelected(range) }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (active) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
private fun ErrorNotice(message: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(12.dp),
        )
    }
}

@Composable
private fun EmptyState(message: String, modifier: Modifier = Modifier) {
    Text(
        text = message,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(32.dp),
    )
}

/**
 * Grouped with dots, the Vietnamese convention, and no currency symbol — the
 * unit is on the label above so it is not repeated on every figure.
 */
private val MoneyFormat = DecimalFormat(
    "#,##0",
    DecimalFormatSymbols(Locale.US).apply { groupingSeparator = '.' },
)

private val DecimalFormat1 = DecimalFormat(
    "#,##0.#",
    DecimalFormatSymbols(Locale.US).apply {
        groupingSeparator = '.'
        decimalSeparator = ','
    },
)

private fun formatMoney(value: Long): String = MoneyFormat.format(value)

private fun formatDecimal(value: Double): String = DecimalFormat1.format(value)

// -----------------------------------------------------------------------------
// Previews
// -----------------------------------------------------------------------------

private val SampleOverview = DashboardOverview(
    date = LocalDate.of(2026, 8, 16),
    today = TodayFigures(
        revenue = 4_250_000,
        orderCount = 6,
        visitDone = 8,
        visitPlanned = 12,
        skuPerOrder = 4.5,
    ),
    month = MonthFigures(
        revenue = 68_400_000,
        revenueTarget = 120_000_000,
        orderCount = 42,
        orderTarget = null,
    ),
    charts = mapOf(
        ChartRange.THIS_WEEK to listOf(
            SalesPoint("T2", 3_200_000),
            SalesPoint("T3", 5_100_000),
            SalesPoint("T4", 2_400_000),
            SalesPoint("T5", 6_800_000),
            SalesPoint("T6", 4_100_000),
            SalesPoint("T7", 4_250_000),
            SalesPoint("CN", 0),
        ),
    ),
)

@Preview(name = "Overview", showBackground = true, heightDp = 900)
@Composable
private fun DashboardPreview() {
    MyeSalesTheme {
        DashboardContent(
            state = DashboardUiState(loading = false, overview = SampleOverview),
            onOpenDrawer = {},
            onRefresh = {},
            onRangeSelected = {},
        )
    }
}

/** A brand-new rep, and the case where every figure is legitimately zero. */
@Preview(name = "Overview - empty month", showBackground = true, heightDp = 900)
@Composable
private fun DashboardEmptyPreview() {
    MyeSalesTheme {
        DashboardContent(
            state = DashboardUiState(
                loading = false,
                overview = DashboardOverview(
                    date = LocalDate.of(2026, 8, 16),
                    today = TodayFigures(0, 0, 0, 0, 0.0),
                    month = MonthFigures(0, null, 0, null),
                    charts = mapOf(
                        ChartRange.THIS_WEEK to listOf(
                            SalesPoint("T2", 0),
                            SalesPoint("T3", 0),
                            SalesPoint("T4", 0),
                            SalesPoint("T5", 0),
                            SalesPoint("T6", 0),
                            SalesPoint("T7", 0),
                            SalesPoint("CN", 0),
                        ),
                    ),
                ),
            ),
            onOpenDrawer = {},
            onRefresh = {},
            onRangeSelected = {},
        )
    }
}
