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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.tinhcd.myesalessfa.core.ui.ErrorBox
import com.tinhcd.myesalessfa.core.ui.LoadingBox
import com.tinhcd.myesalessfa.core.ui.formatDong
import com.tinhcd.myesalessfa.core.ui.theme.MyeSalesTheme
import com.tinhcd.myesalessfa.core.ui.theme.brand
import com.tinhcd.myesalessfa.domain.model.CustomerInfo

/**
 * One outlet's card, opened from the route before the rep walks in.
 *
 * Read-only and deliberately so. Class, channel and credit limit are head
 * office's to set, and the two fields a rep might genuinely want to correct —
 * phone and address — belong to the registration flow where the change is
 * reviewed, not to a screen tapped between two stops.
 *
 * A port of the legacy customer info tab, which sat behind check-in. Here it is
 * reachable from the route, which is where the question actually gets asked:
 * how much credit does this shop have before I stand in front of the owner.
 */
@Composable
fun CustomerDetailScreen(
    onBack: () -> Unit,
    viewModel: CustomerDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    CustomerDetailContent(
        state = state,
        onBack = onBack,
        onRetry = viewModel::load,
    )
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun CustomerDetailContent(
    state: CustomerDetailUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Thông tin khách hàng") },
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
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            val info = state.info
            when {
                state.loading -> LoadingBox()
                state.error != null -> ErrorBox(state.error, onRetry = onRetry)
                info == null -> ErrorBox("Không tìm thấy khách hàng này", onRetry = onRetry)
                else -> Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                ) {
                    CustomerHeader(info)
                    Spacer(Modifier.height(16.dp))
                    DetailCard(info)
                }
            }
        }
    }
}

@Composable
private fun CustomerHeader(info: CustomerInfo) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.size(64.dp),
        ) {
            if (info.avatarUrl.isNullOrBlank()) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.Storefront,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(26.dp),
                    )
                }
            } else {
                AsyncImage(
                    model = info.avatarUrl,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        Spacer(Modifier.width(12.dp))

        Column(Modifier.weight(1f)) {
            Text(
                text = info.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
            )
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

/**
 * The rows the legacy screen showed, in the order it showed them.
 *
 * Order kept on purpose. A rep who has used the old app reads down this card
 * looking for the credit limit at the bottom, and moving it to be tidier costs
 * them a scan of the whole list every time.
 */
@Composable
private fun DetailCard(info: CustomerInfo) {
    Card(
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 12.dp)) {
            DetailRow("Mã khách hàng", info.code)
            DetailRow("Điện thoại", info.phone, missing = "Chưa có số điện thoại")
            DetailRow("Tên khách hàng", info.name)
            // The legacy proc substitutes the shop name when no contact is
            // recorded. Said plainly instead: a rep who reads the owner's name
            // back to a shop that never gave one looks like they guessed.
            DetailRow("Người liên hệ", info.contactName, missing = "Chưa ghi nhận")
            DetailRow("Địa chỉ", info.address, missing = "Chưa có địa chỉ")
            DetailRow("Kênh bán hàng", info.channelName, missing = "Chưa phân kênh")
            DetailRow("Nhóm khách hàng", info.className, missing = "Chưa phân nhóm")
            DetailRow("Loại cửa hàng", info.shopTypeName, missing = "Chưa phân loại")
            DetailRow("Doanh số tháng này", formatDong(info.monthRevenue))
            DetailRow("Hạn mức công nợ", creditLimitText(info.creditLimit), last = true)
        }
    }
}

/**
 * Null and zero are different answers and neither is a number worth printing.
 *
 * Zero shown as "0 đ" reads as a limit that has been used up; no limit at all
 * shown the same way tells a rep they may not sell on terms when nobody has
 * decided that.
 */
private fun creditLimitText(limit: Long?): String = when {
    limit == null -> "Chưa thiết lập"
    limit == 0L -> "Chỉ bán tiền mặt"
    else -> formatDong(limit)
}

@Composable
private fun DetailRow(
    label: String,
    value: String?,
    missing: String = "—",
    last: Boolean = false,
) {
    val blank = value.isNullOrBlank()

    Column {
        Row(
            verticalAlignment = Alignment.Top,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(16.dp))
            Text(
                text = if (blank) missing else value.orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (blank) FontWeight.Normal else FontWeight.Medium,
                // Greyed when absent, so a scan down the value column separates
                // what is recorded from what is not without reading every line.
                color = if (blank) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1f),
            )
        }
        if (!last) HorizontalDivider()
    }
}

private val sample = CustomerInfo(
    customerId = "c1",
    code = "KH001",
    name = "Tạp hóa Minh Anh",
    phone = "0281234001",
    address = "45 Nguyễn Trãi, Phú Hòa, Thủ Dầu Một",
    avatarUrl = null,
    contactName = "Chị Minh Anh",
    channelName = "Kênh truyền thống",
    className = "Nhóm A",
    shopTypeName = "Tạp hóa",
    creditLimit = 20_000_000,
    monthRevenue = 4_560_000,
)

@Preview(showBackground = true)
@Composable
private fun CustomerDetailPreview() {
    MyeSalesTheme {
        CustomerDetailContent(
            state = CustomerDetailUiState(loading = false, info = sample),
            onBack = {},
            onRetry = {},
        )
    }
}

/** The other half of the screen: an outlet head office has barely filled in. */
@Preview(showBackground = true)
@Composable
private fun CustomerDetailSparsePreview() {
    MyeSalesTheme {
        CustomerDetailContent(
            state = CustomerDetailUiState(
                loading = false,
                info = sample.copy(
                    contactName = null,
                    address = null,
                    className = null,
                    creditLimit = null,
                    monthRevenue = 0,
                ),
            ),
            onBack = {},
            onRetry = {},
        )
    }
}
