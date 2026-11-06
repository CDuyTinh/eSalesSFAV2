package com.tinhcd.myesalessfa.feature.reports

import androidx.compose.foundation.background
import com.tinhcd.myesalessfa.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.svg.SvgDecoder
import com.tinhcd.myesalessfa.core.ui.theme.MyeSalesTheme
import com.tinhcd.myesalessfa.core.ui.theme.brand

/**
 * The report menu — six named reports, one tap each.
 *
 * Laid out as the legacy app lays it out (`report_menu_form.dart`): a header
 * band in the brand colour with its two bottom corners rounded, and the
 * reports as a two-column grid of cards, each with the legacy illustration,
 * starting over the band's lower edge. These are six different questions asked
 * at six different moments, so each gets its own tile rather than a tab.
 */
@Composable
fun ReportsMenuScreen(
    onBack: () -> Unit,
    onOpenReport: (ReportKind) -> Unit,
) {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // The band: behind the status bar, the title and the top of the grid.
        Box(
            Modifier
                .fillMaxWidth()
                .height(HeaderHeight)
                .background(
                    Brush.linearGradient(listOf(MaterialTheme.brand.header, MaterialTheme.brand.header.darken())),
                    RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp),
                ),
        )
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(56.dp).padding(horizontal = 4.dp)) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back), tint = MaterialTheme.brand.onHeader)
                }
                Text(
                    stringResource(R.string.sfb_187),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.brand.onHeader,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(ReportKind.entries, key = { it.wire }) { kind ->
                    ReportTile(kind = kind, onClick = { onOpenReport(kind) })
                }
            }
        }
    }
}

/** Tall enough for the status bar, the title row and the first cards' tops. */
private val HeaderHeight = 170.dp

@Composable
private fun ReportTile(kind: ReportKind, onClick: () -> Unit) {
    val context = LocalContext.current
    // Built once per tile: the decoder and the asset path never change.
    val request = remember(kind) {
        ImageRequest.Builder(context)
            .data("file:///android_asset/report/${illustrationFor(kind)}")
            .decoderFactory(SvgDecoder.Factory())
            .build()
    }
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().aspectRatio(1.2f),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
    ) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            AsyncImage(model = request, contentDescription = null, modifier = Modifier.size(60.dp))
            Text(
                stringResource(kind.label),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                maxLines = 2,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

/** The legacy app's picture for each report (assets/report, from its assets/images). */
private fun illustrationFor(kind: ReportKind): String = when (kind) {
    ReportKind.ACTIVITIES -> "report_6_image.svg"
    ReportKind.SALES -> "report_2_image.svg"
    ReportKind.ORDERS -> "report_5_image.svg"
    ReportKind.SKU -> "report_4_image.svg"
    ReportKind.TRADE -> "report_3_image.svg"
    ReportKind.WORK_RESULT -> "report_1_image.svg"
}

/** The band's far corner, a shade deeper, as the legacy header's gradient runs. */
internal fun Color.darken(): Color = Color(red * 0.8f, green * 0.8f, blue * 0.8f, alpha)

@Preview(name = "Danh mục báo cáo", showBackground = true, heightDp = 700)
@Composable
private fun ReportsMenuPreview() {
    MyeSalesTheme {
        ReportsMenuScreen(onBack = {}, onOpenReport = {})
    }
}
