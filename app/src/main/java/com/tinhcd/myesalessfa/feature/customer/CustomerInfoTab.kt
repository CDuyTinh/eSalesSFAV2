package com.tinhcd.myesalessfa.feature.customer

import androidx.compose.foundation.layout.Box
import com.tinhcd.myesalessfa.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.tinhcd.myesalessfa.core.ui.ErrorBox
import com.tinhcd.myesalessfa.core.ui.SkeletonDetailCard
import com.tinhcd.myesalessfa.core.ui.formatDong
import com.tinhcd.myesalessfa.core.ui.theme.MyeSalesTheme
import com.tinhcd.myesalessfa.domain.model.CustomerInfo

/**
 * Thông tin khách hàng — the second tab, and the one the screen opens on before
 * a check-in has happened.
 *
 * Read-only and deliberately so. Class, channel and credit limit are head
 * office's to set, and the two fields a rep might genuinely want to correct —
 * phone and address — belong to the registration flow where the change is
 * reviewed, not to a screen tapped between two stops.
 */
@Composable
fun CustomerInfoTab(
    state: CustomerDetailUiState,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val info = state.info
    Box(modifier.fillMaxSize()) {
        when {
            state.loading -> SkeletonDetailCard()
            state.error != null -> ErrorBox(state.error, onRetry = onRetry)
            info == null -> ErrorBox(stringResource(R.string.sfa_2), onRetry = onRetry)
            else -> Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            ) {
                DetailCard(info)
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
            DetailRow(stringResource(R.string.sfb_30), info.code)
            DetailRow(stringResource(R.string.account_phone), info.phone, missing = stringResource(R.string.route_no_phone))
            DetailRow(stringResource(R.string.sfb_31), info.name)
            // The legacy proc substitutes the shop name when no contact is
            // recorded. Said plainly instead: a rep who reads the owner's name
            // back to a shop that never gave one looks like they guessed.
            DetailRow(stringResource(R.string.order_contact_person), info.contactName, missing = stringResource(R.string.sfb_32))
            DetailRow(stringResource(R.string.order_address), info.address, missing = stringResource(R.string.route_no_address))
            DetailRow(stringResource(R.string.sfb_33), info.channelName, missing = stringResource(R.string.sfb_34))
            DetailRow(stringResource(R.string.sfa_93), info.className, missing = stringResource(R.string.sfb_35))
            DetailRow(stringResource(R.string.sfa_95), info.shopTypeName, missing = stringResource(R.string.sfb_36))
            DetailRow(stringResource(R.string.sfb_37), formatDong(info.monthRevenue))
            DetailRow(stringResource(R.string.sfb_38), creditLimitText(info.creditLimit), last = true)
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

internal val sampleCustomerInfo = CustomerInfo(
    customerId = "c1",
    code = "KH001",
    name = "Tạp hóa Minh Anh",
    phone = "0281234001",
    address = "45 Nguyễn Trãi, Phú Hòa, Thủ Dầu Một",
    avatarUrl = null,
    lat = 10.9812,
    lng = 106.6524,
    contactName = "Chị Minh Anh",
    channelName = "Kênh truyền thống",
    className = "Nhóm A",
    shopTypeName = "Tạp hóa",
    creditLimit = 20_000_000,
    monthRevenue = 4_560_000,
)

@Preview(showBackground = true)
@Composable
private fun CustomerInfoTabPreview() {
    MyeSalesTheme {
        CustomerInfoTab(
            state = CustomerDetailUiState(loading = false, info = sampleCustomerInfo),
            onRetry = {},
        )
    }
}

/** The other half of the tab: an outlet head office has barely filled in. */
@Preview(showBackground = true)
@Composable
private fun CustomerInfoTabSparsePreview() {
    MyeSalesTheme {
        CustomerInfoTab(
            state = CustomerDetailUiState(
                loading = false,
                info = sampleCustomerInfo.copy(
                    contactName = null,
                    address = null,
                    className = null,
                    creditLimit = null,
                    monthRevenue = 0,
                ),
            ),
            onRetry = {},
        )
    }
}
