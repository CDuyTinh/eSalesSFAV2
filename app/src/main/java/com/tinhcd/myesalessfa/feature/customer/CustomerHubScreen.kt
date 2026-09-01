package com.tinhcd.myesalessfa.feature.customer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import coil3.compose.AsyncImage
import com.tinhcd.myesalessfa.core.ui.theme.MyeSalesTheme
import com.tinhcd.myesalessfa.core.ui.theme.brand
import com.tinhcd.myesalessfa.feature.incall.InCallTab

/**
 * Everything about one outlet, in the shape the app this replaces gave it.
 *
 * Tapping a stop lands here rather than straight in the check-in. The legacy app
 * did the same, and the reason holds: standing outside a shop, the rep may want
 * the credit limit or what the shop took last time before they commit to a
 * visit, and a check-in is a timestamped record that is awkward to undo.
 *
 * The check-in itself did not move. It is still the chip on the route card,
 * which is where a rep working a fifty-stop day expects it.
 */
private enum class HubTab(val title: String) {
    WORK("Công việc"),
    INFO("Thông tin"),
    ORDERS("Lịch sử đơn hàng"),
    PROGRAMS("Chương trình"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomerHubScreen(
    /**
     * Null until the rep has checked in. Drives whether the work tab exists at
     * all — matching the legacy, which hid it for the same reason: there is no
     * visit to do work against.
     */
    visitId: String?,
    onOpenStep: (formId: String) -> Unit,
    onCheckedOut: () -> Unit,
    onBack: () -> Unit,
    detailViewModel: CustomerDetailViewModel = hiltViewModel(),
    ordersViewModel: CustomerOrdersViewModel = hiltViewModel(),
) {
    val detail by detailViewModel.state.collectAsStateWithLifecycle()
    val orders by ordersViewModel.state.collectAsStateWithLifecycle()

    val tabs = remember(visitId) {
        if (visitId == null) HubTab.entries - HubTab.WORK else HubTab.entries.toList()
    }

    // Opens on the work tab mid-visit and on the details before one, which is
    // what each moment is actually asking about. Saveable so a rotation does not
    // throw the rep back to the first tab.
    var selected by rememberSaveable(visitId) {
        mutableStateOf(if (visitId == null) HubTab.INFO else HubTab.WORK)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(detail.info?.name ?: "Khách hàng", maxLines = 1) },
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
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // One header above the tabs rather than one per tab: the shop is the
            // subject of all four, and repeating its name inside each of them
            // would push the actual content down four times over.
            CustomerHeader(detail)

            ScrollableTabRow(
                selectedTabIndex = tabs.indexOf(selected).coerceAtLeast(0),
                edgePadding = 12.dp,
            ) {
                tabs.forEach { tab ->
                    Tab(
                        selected = tab == selected,
                        onClick = { selected = tab },
                        text = {
                            Text(
                                tab.title,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                    )
                }
            }

            HorizontalDivider()

            when (selected) {
                // Composed only inside this branch, and only when there is a
                // visit: InCallViewModel reads a visit id it requires to exist.
                HubTab.WORK -> InCallTab(
                    onOpenStep = onOpenStep,
                    onCheckedOut = onCheckedOut,
                )

                HubTab.INFO -> CustomerInfoTab(
                    state = detail,
                    onRetry = detailViewModel::load,
                )

                HubTab.ORDERS -> CustomerOrdersTab(
                    state = orders,
                    onRetry = ordersViewModel::load,
                    onToggleOrder = ordersViewModel::onToggleOrder,
                )

                HubTab.PROGRAMS -> ProgramsTab()
            }
        }
    }
}

@Composable
private fun CustomerHeader(state: CustomerDetailUiState) {
    val info = state.info

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.size(56.dp),
        ) {
            if (info?.avatarUrl.isNullOrBlank()) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.Storefront,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(24.dp),
                    )
                }
            } else {
                AsyncImage(
                    model = info?.avatarUrl,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        Spacer(Modifier.width(12.dp))

        Column(Modifier.weight(1f)) {
            Text(
                text = info?.name ?: "Đang tải…",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (info != null) {
                Spacer(Modifier.height(4.dp))
                Surface(
                    color = MaterialTheme.colorScheme.primary,
                    contentColor = Color.White,
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text(
                        text = info.code,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
        }
    }
}

/**
 * Chương trình — the fourth tab, with nothing behind it yet.
 *
 * Says so rather than showing an empty list. The legacy tab lists display,
 * loyalty and POSM programmes with a progress figure per programme, and none of
 * those tables exist in this schema — an empty list would read as "this shop is
 * in no programmes", which is a claim this build cannot make.
 */
@Composable
private fun ProgramsTab() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Default.Campaign,
            contentDescription = null,
            modifier = Modifier.size(40.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "Chưa có trong bản này",
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
        )
        Text(
            text = "Chương trình trưng bày, tích lũy và POSM sẽ hiện ở đây khi " +
                "dữ liệu chương trình được đưa lên hệ thống.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Preview(showBackground = true, heightDp = 900)
@Composable
private fun CustomerHubPreview() {
    MyeSalesTheme {
        Column {
            CustomerHeader(CustomerDetailUiState(loading = false, info = sampleCustomerInfo))
            HorizontalDivider()
            CustomerInfoTab(
                state = CustomerDetailUiState(loading = false, info = sampleCustomerInfo),
                onRetry = {},
            )
        }
    }
}
