package com.tinhcd.myesalessfa.feature.reports

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material.icons.filled.Workspaces
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.tinhcd.myesalessfa.core.ui.Kpi
import com.tinhcd.myesalessfa.core.ui.theme.MyeSalesTheme
import com.tinhcd.myesalessfa.core.ui.theme.brand

/**
 * The report menu — six named reports, one tap each.
 *
 * The six used to share a screen behind a scrolling tab row. Six labels do not
 * fit across a phone, so half of them were always off the edge: a rep had to
 * swipe the bar to find out what existed. Naming them on a page of their own is
 * what the legacy does (`report_menu_form.dart`), and it is the right shape —
 * these are six different questions asked at six different moments, not six
 * views of one thing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportsMenuScreen(
    onBack: () -> Unit,
    onOpenReport: (ReportKind) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Báo cáo") },
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
        Box(Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(ReportKind.entries, key = { it.wire }) { kind ->
                    ReportMenuCard(kind = kind, onClick = { onOpenReport(kind) })
                }
            }
        }
    }
}

@Composable
private fun ReportMenuCard(kind: ReportKind, onClick: () -> Unit) {
    val ramp = rampFor(kind)

    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The same ramp the report's own tiles use, so the colour carries
            // from this row into the screen it opens.
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = Color.Transparent,
                modifier = Modifier.size(44.dp),
            ) {
                Box(
                    modifier = Modifier.background(Brush.verticalGradient(ramp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        iconFor(kind),
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }

            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    kind.label,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    kind.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

private fun iconFor(kind: ReportKind): ImageVector = when (kind) {
    ReportKind.ACTIVITIES -> Icons.Default.CalendarMonth
    ReportKind.SALES -> Icons.Default.TrendingUp
    ReportKind.ORDERS -> Icons.Default.ReceiptLong
    ReportKind.SKU -> Icons.Default.Inventory2
    ReportKind.TRADE -> Icons.Default.Workspaces
    ReportKind.WORK_RESULT -> Icons.Default.TaskAlt
}

/** Each report keeps one colour, from this menu through to its own tiles. */
internal fun rampFor(kind: ReportKind): List<Color> = when (kind) {
    ReportKind.ACTIVITIES -> Kpi.Blue
    ReportKind.SALES -> Kpi.Violet
    ReportKind.ORDERS -> Kpi.Green
    ReportKind.SKU -> Kpi.Orange
    ReportKind.TRADE -> Kpi.Violet
    ReportKind.WORK_RESULT -> Kpi.Green
}

@Preview(name = "Danh mục báo cáo", showBackground = true, heightDp = 700)
@Composable
private fun ReportsMenuPreview() {
    MyeSalesTheme {
        ReportsMenuScreen(onBack = {}, onOpenReport = {})
    }
}
