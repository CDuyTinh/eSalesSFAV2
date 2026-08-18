package com.tinhcd.myesalessfa.feature.sitestock

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.tinhcd.myesalessfa.domain.model.Site
import com.tinhcd.myesalessfa.domain.model.SiteStockItem
import com.tinhcd.myesalessfa.domain.model.SiteStockView
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * What the warehouse has, so a rep can stop promising what it does not.
 *
 * Read-only, and it says how old the figures are. A stock number with no age on
 * it invites a rep to treat Tuesday's count as this morning's, and the difference
 * is a case of something that turns up short at the shop.
 */
@Composable
fun SiteStockScreen(
    onBack: () -> Unit,
    viewModel: SiteStockViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    SiteStockContent(
        state = state,
        onBack = onBack,
        onRetry = viewModel::refresh,
        onSiteSelected = { viewModel.load(it.siteId) },
        onQueryChanged = viewModel::onQueryChanged,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SiteStockContent(
    state: SiteStockUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onSiteSelected: (Site) -> Unit,
    onQueryChanged: (String) -> Unit,
) {
    val view = state.view

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Kho xuất hàng") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Quay lại",
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onRetry, enabled = !state.loading) {
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
            when {
                state.loading -> LoadingBox()
                state.error != null -> ErrorBox(state.error, onRetry = onRetry)
                view.sites.isEmpty() ->
                    ErrorBox("Chi nhánh chưa có kho xuất hàng nào")

                else -> Column(Modifier.fillMaxSize()) {
                    // Only when there is a choice to make. One warehouse and a
                    // dropdown is a control that does nothing.
                    if (view.sites.size > 1) {
                        SitePicker(
                            sites = view.sites,
                            selected = view.site,
                            onSelected = onSiteSelected,
                            modifier = Modifier.padding(
                                start = 16.dp,
                                end = 16.dp,
                                top = 12.dp,
                            ),
                        )
                    } else {
                        view.site?.let { site ->
                            Text(
                                text = listOfNotNull(site.name, site.address)
                                    .joinToString(" · "),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(
                                    start = 16.dp,
                                    end = 16.dp,
                                    top = 12.dp,
                                ),
                            )
                        }
                    }

                    FreshnessLine(view = view)

                    OutlinedTextField(
                        value = view.query,
                        onValueChange = onQueryChanged,
                        label = { Text("Tìm sản phẩm") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    )

                    if (view.items.isEmpty()) {
                        ErrorBox("Kho này chưa có số liệu tồn")
                    } else {
                        LazyColumn(
                            contentPadding = PaddingValues(
                                start = 16.dp,
                                end = 16.dp,
                                bottom = 16.dp,
                            ),
                        ) {
                            if (view.visible.isEmpty()) {
                                item {
                                    Text(
                                        text = "Không tìm thấy sản phẩm nào",
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 32.dp),
                                    )
                                }
                            }

                            items(view.visible, key = { it.productId }) { item ->
                                StockRow(item)
                                HorizontalDivider()
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SitePicker(
    sites: List<Site>,
    selected: Site?,
    onSelected: (Site) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }

    Box(modifier) {
        OutlinedTextField(
            value = selected?.let { "${it.code} · ${it.name}" } ?: "",
            onValueChange = {},
            readOnly = true,
            enabled = false,
            label = { Text("Kho") },
            trailingIcon = { Icon(Icons.Default.ArrowDropDown, contentDescription = null) },
            modifier = Modifier
                .fillMaxWidth()
                .clickable { open = true },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            sites.forEach { site ->
                DropdownMenuItem(
                    text = { Text("${site.code} · ${site.name}") },
                    trailingIcon = {
                        if (site.siteId == selected?.siteId) {
                            Icon(Icons.Default.Check, contentDescription = null)
                        }
                    },
                    onClick = {
                        onSelected(site)
                        open = false
                    },
                )
            }
        }
    }
}

/**
 * How old the oldest line is, not the newest.
 *
 * Quoting the newest would let one product updated a minute ago vouch for a list
 * where everything else is a week stale.
 */
@Composable
private fun FreshnessLine(view: SiteStockView) {
    val oldest = view.oldestUpdateEpochMs ?: return
    val age = Duration.between(Instant.ofEpochMilli(oldest), Instant.now())
    val stale = age.toHours() >= 24

    Row(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Số liệu tính đến ${TimeFormat.format(
                Instant.ofEpochMilli(oldest).atZone(ZoneId.systemDefault()),
            )} · ${age.describe()}",
            style = MaterialTheme.typography.labelSmall,
            color = if (stale) StaleAmber else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (view.outOfStockCount > 0) {
            Surface(
                color = OutRed.copy(alpha = 0.14f),
                contentColor = OutRed,
                shape = CircleShape,
            ) {
                Text(
                    text = "${view.outOfStockCount} hết hàng",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun StockRow(item: SiteStockItem) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = item.productName,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = item.productCode,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = if (item.isOutOfStock) "Hết hàng" else "${item.qtyBase}",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = if (item.isOutOfStock) {
                    OutRed
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
            if (!item.isOutOfStock) {
                Text(
                    text = item.baseUom,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Coarse on purpose: nobody plans a round on the difference between 3h and 4h. */
private fun Duration.describe(): String = when {
    toMinutes() < 60 -> "${toMinutes().coerceAtLeast(0)} phút trước"
    toHours() < 24 -> "${toHours()} giờ trước"
    else -> "${toDays()} ngày trước"
}

private val OutRed = Color(0xFFD5262B)
private val StaleAmber = Color(0xFFF5A202)

private val TimeFormat = DateTimeFormatter.ofPattern("HH:mm dd/MM")

// -----------------------------------------------------------------------------
// Previews
// -----------------------------------------------------------------------------

private val SampleView = SiteStockView(
    sites = listOf(
        Site("s1", "KHO01", "Kho chính Thủ Dầu Một", "12 Yersin"),
        Site("s2", "KHO02", "Kho Lái Thiêu", null),
    ),
    siteId = "s1",
    items = listOf(
        SiteStockItem("p1", "NGK001", "Nước ngọt Coca-Cola 330ml", "PCS", 480, null),
        SiteStockItem("p2", "NGK003", "Nước suối Aquafina 500ml", "PCS", 0, null),
    ),
)

@Preview(name = "Kho xuất hàng", showBackground = true, heightDp = 800)
@Composable
private fun SiteStockPreview() {
    MyeSalesTheme {
        SiteStockContent(
            state = SiteStockUiState(loading = false, view = SampleView),
            onBack = {},
            onRetry = {},
            onSiteSelected = {},
            onQueryChanged = {},
        )
    }
}
