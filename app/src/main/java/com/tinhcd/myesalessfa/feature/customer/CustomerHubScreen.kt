package com.tinhcd.myesalessfa.feature.customer

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.SubcomposeAsyncImage
import com.tinhcd.myesalessfa.core.ui.theme.MyeSalesTheme
import com.tinhcd.myesalessfa.core.ui.theme.brand
import com.tinhcd.myesalessfa.domain.model.CustomerInfo
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

/** Legacy banner height, which the shop front has to be recognisable at. */
private val BannerHeight = 174.dp

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

    // No Scaffold and no app bar: the banner runs under the status bar, and the
    // back button floats on top of it. That is the legacy layout, and it is what
    // buys the shop front the full width of the screen.
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        CustomerBanner(info = detail.info, onBack = onBack)

        TabPills(
            tabs = tabs,
            selected = selected,
            onSelect = { selected = it },
        )

        when (selected) {
            // Composed only inside this branch, and only when there is a visit:
            // InCallViewModel reads a visit id it requires to exist.
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

/**
 * The shop front, with the name written across the bottom of it.
 *
 * A scrim over the lower third that the legacy does not have. There the name is
 * plain white on whatever the photo happens to be, which is legible right up
 * until a rep photographs a shop with a pale awning and the name disappears.
 * The gradient costs nothing and removes the whole class of that problem.
 */
@Composable
private fun CustomerBanner(info: CustomerInfo?, onBack: () -> Unit) {
    val context = LocalContext.current

    Box(
        Modifier
            .fillMaxWidth()
            .height(BannerHeight)
            .clip(RoundedCornerShape(bottomStart = 20.dp, bottomEnd = 20.dp)),
    ) {
        if (info?.avatarUrl.isNullOrBlank()) {
            BannerPlaceholder()
        } else {
            SubcomposeAsyncImage(
                model = info?.avatarUrl,
                contentDescription = null,
                loading = { BannerPlaceholder() },
                error = { BannerPlaceholder() },
                modifier = Modifier.fillMaxSize(),
            )
        }

        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.45f to Color.Transparent,
                        1f to Color.Black.copy(alpha = 0.65f),
                    ),
                ),
        )

        CircleButton(
            icon = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = "Quay lại",
            onClick = onBack,
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(start = 12.dp, top = 8.dp),
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(start = 16.dp, end = 12.dp, bottom = 14.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = info?.name ?: "Đang tải…",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (info != null) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = info.code,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.85f),
                    )
                }
            }

            Spacer(Modifier.width(12.dp))

            // Both hide rather than sit disabled. A shop with no phone number
            // and one whose number simply will not dial look identical on a
            // greyed button, and the second never happens.
            val phone = info?.phone
            if (!phone.isNullOrBlank()) {
                CircleButton(
                    icon = Icons.Default.Phone,
                    contentDescription = "Gọi ${info.name}",
                    onClick = {
                        context.startActivity(
                            Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone")),
                        )
                    },
                )
                Spacer(Modifier.width(10.dp))
            }

            val lat = info?.lat
            val lng = info?.lng
            if (lat != null && lng != null) {
                CircleButton(
                    icon = Icons.Default.Map,
                    contentDescription = "Xem trên bản đồ",
                    onClick = {
                        // Hands off to whatever map the phone has, with the
                        // shop's name as the pin label. Nothing in this app
                        // needs to draw a map to answer "where is it".
                        val label = Uri.encode(info.name)
                        context.startActivity(
                            Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse("geo:$lat,$lng?q=$lat,$lng($label)"),
                            ),
                        )
                    },
                )
            }
        }
    }
}

/** Stands in for a shop front nobody has photographed. */
@Composable
private fun BannerPlaceholder() {
    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.linearGradient(
                    listOf(
                        MaterialTheme.brand.header,
                        MaterialTheme.colorScheme.primaryContainer,
                    ),
                ),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Default.Storefront,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.55f),
            modifier = Modifier.size(56.dp),
        )
    }
}

@Composable
private fun CircleButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = CircleShape,
        color = Color.White.copy(alpha = 0.9f),
        modifier = modifier
            .size(40.dp)
            .clickable(onClick = onClick),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                icon,
                contentDescription = contentDescription,
                tint = MaterialTheme.brand.header,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/**
 * Four pills sharing the width, the way the legacy row does.
 *
 * Not a ScrollableTabRow, which is what this was first: four Vietnamese labels
 * do not fit one line on a 360dp phone, so the row scrolled and the tab a rep
 * wanted was often off-screen. Sharing the width and letting the label wrap to
 * two lines keeps all four visible, which is the property that matters — a tab
 * you cannot see is one you do not know is there.
 */
@Composable
private fun TabPills(
    tabs: List<HubTab>,
    selected: HubTab,
    onSelect: (HubTab) -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .fillMaxWidth()
            // Every pill as tall as the tallest, which is what the legacy's
            // IntrinsicHeight buys it. Without this, "Lịch sử đơn hàng" wraps to
            // two lines and stands a row of four chips at three different
            // heights.
            .height(IntrinsicSize.Min)
            .padding(horizontal = 12.dp, vertical = 12.dp),
    ) {
        tabs.forEach { tab ->
            val active = tab == selected
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = if (active) {
                    MaterialTheme.brand.header
                } else {
                    MaterialTheme.colorScheme.surface
                },
                border = BorderStroke(
                    width = 1.dp,
                    color = if (active) {
                        MaterialTheme.brand.header
                    } else {
                        MaterialTheme.colorScheme.outlineVariant
                    },
                ),
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clickable { if (!active) onSelect(tab) },
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxHeight()
                        .padding(horizontal = 4.dp, vertical = 8.dp),
                ) {
                    Text(
                        text = tab.title,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        fontSize = 12.sp,
                        lineHeight = 15.sp,
                        fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
                        color = if (active) {
                            Color.White
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
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
            CustomerBanner(info = sampleCustomerInfo, onBack = {})
            TabPills(
                tabs = HubTab.entries.toList(),
                selected = HubTab.INFO,
                onSelect = {},
            )
            CustomerInfoTab(
                state = CustomerDetailUiState(loading = false, info = sampleCustomerInfo),
                onRetry = {},
            )
        }
    }
}
