package com.tinhcd.myesalessfa.feature.customer

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.tinhcd.myesalessfa.core.ui.ErrorBox
import com.tinhcd.myesalessfa.core.ui.LoadingBox
import com.tinhcd.myesalessfa.core.ui.formatDong
import com.tinhcd.myesalessfa.core.ui.theme.MyeSalesTheme
import com.tinhcd.myesalessfa.core.ui.theme.brand
import com.tinhcd.myesalessfa.domain.model.CustomerInfo
import com.tinhcd.myesalessfa.domain.model.DisplayProgram
import com.tinhcd.myesalessfa.domain.model.DisplayProgramOffer
import com.tinhcd.myesalessfa.domain.model.LoyaltyCountsBy
import com.tinhcd.myesalessfa.domain.model.LoyaltyLevel
import com.tinhcd.myesalessfa.domain.model.LoyaltyProgram
import com.tinhcd.myesalessfa.domain.model.LoyaltyProgramOffer
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

/**
 * The shop front has to be recognisable at this height, and it also has to carry
 * the status bar, the back button and two lines of name across the bottom.
 *
 * The legacy's 174 is measured in its own width-scaled units, not dp, and taken
 * literally here it left the photo squeezed between the status bar and the name —
 * about 100dp of actual picture on a tall phone.
 */
private val BannerHeight = 260.dp

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
    programsViewModel: CustomerProgramsViewModel = hiltViewModel(),
) {
    val detail by detailViewModel.state.collectAsStateWithLifecycle()
    val orders by ordersViewModel.state.collectAsStateWithLifecycle()
    val programs by programsViewModel.state.collectAsStateWithLifecycle()

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

            HubTab.PROGRAMS -> ProgramsTab(
                state = programs,
                canRegister = programsViewModel.canRegister,
                onRetry = programsViewModel::load,
                onToggle = programsViewModel::onToggle,
                onRegister = programsViewModel::onRegister,
                onRegisterLoyalty = programsViewModel::onRegisterLoyalty,
            )
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
 * Chương trình — what this outlet is in, and what it could still join.
 *
 * The legacy tab lists three families: trưng bày, tích luỹ, POSM. Display
 * programmes are here and signing an outlet up is the rep's own act
 * (InsertTradeRegis); POSM has a step of its own in the call; loyalty has no
 * schema in this build yet, and the footnote says so rather than letting the
 * list imply this is the whole picture.
 */
@Composable
private fun ProgramsTab(
    state: CustomerProgramsUiState,
    canRegister: Boolean,
    onRetry: () -> Unit,
    onToggle: (String) -> Unit,
    onRegister: (programId: String, levelId: String) -> Unit,
    onRegisterLoyalty: (programId: String, levelId: String) -> Unit,
) {
    when {
        state.loading -> LoadingBox()

        state.error != null && state.open.isEmpty() && state.joined.isEmpty() ->
            ErrorBox(state.error, onRetry = onRetry)

        else -> LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.joined.isNotEmpty()) {
                item(key = "h-joined") { SectionLabel("Đang tham gia") }
                items(state.joined, key = { "j-" + it.programId }) { program ->
                    JoinedProgramCard(program)
                }
            }

            if (state.open.isNotEmpty()) {
                item(key = "h-open") {
                    SectionLabel(
                        if (state.joined.isEmpty()) "Có thể đăng ký" else "Chương trình khác",
                    )
                }
                items(state.open, key = { "o-" + it.programId }) { offer ->
                    OpenProgramCard(
                        offer = offer,
                        expanded = state.expanded == offer.programId,
                        canRegister = canRegister,
                        registering = state.registering,
                        onToggle = { onToggle(offer.programId) },
                        onRegister = { levelId -> onRegister(offer.programId, levelId) },
                    )
                }
            }

            if (state.loyaltyJoined.isNotEmpty()) {
                item(key = "h-ljoined") { SectionLabel("Tích lũy đang chạy") }
                items(state.loyaltyJoined, key = { "lj-" + it.programId }) { program ->
                    LoyaltyJoinedCard(program)
                }
            }

            if (state.loyaltyOpen.isNotEmpty()) {
                item(key = "h-lopen") { SectionLabel("Tích lũy có thể đăng ký") }
                items(state.loyaltyOpen, key = { "lo-" + it.programId }) { offer ->
                    LoyaltyOpenCard(
                        offer = offer,
                        expanded = state.expanded == offer.programId,
                        canRegister = canRegister,
                        registering = state.registering,
                        onToggle = { onToggle(offer.programId) },
                        onRegister = { levelId -> onRegisterLoyalty(offer.programId, levelId) },
                    )
                }
            }

            if (state.isEmpty) {
                item(key = "empty") {
                    Text(
                        "Cửa hàng này chưa tham gia chương trình nào, và hôm nay " +
                            "cũng không có chương trình nào đang mở đăng ký.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 24.dp),
                    )
                }
            }

            state.error?.let { message ->
                item(key = "err") {
                    Text(
                        message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            item(key = "note") {
                Text(
                    // Said plainly. A rep who sees only display programmes here
                    // should know why, rather than conclude the shop is in nothing.
                    "Chương trình POSM xem ở bước POSM trong cuộc viếng thăm.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp, bottom = 24.dp),
                )
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyLarge,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
private fun JoinedProgramCard(program: DisplayProgram) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        program.programName,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        program.programCode + " | " + program.levelName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (program.isPending) {
                    ProgramChip("Chờ duyệt", MaterialTheme.colorScheme.tertiary)
                } else {
                    ProgramChip("Đã duyệt", Color(0xFF2E7D32))
                }
            }

            Spacer(Modifier.height(8.dp))
            Text(
                "Chỉ tiêu " + program.requiredFaces + " mặt" +
                    if (program.bonusAmount > 0) {
                        " · thưởng " + formatDong(program.bonusAmount)
                    } else {
                        ""
                    },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // What this visit found, when it has been scored. The audit lives in
            // the display step; this is the same number read back.
            if (program.isScored) {
                Text(
                    "Lần chấm gần nhất: " + (program.countedFaces ?: 0) + " mặt · " +
                        (if (program.achieved == true) "đạt" else "chưa đạt"),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (program.achieved == true) {
                        Color(0xFF2E7D32)
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
            }
        }
    }
}

@Composable
private fun OpenProgramCard(
    offer: DisplayProgramOffer,
    expanded: Boolean,
    canRegister: Boolean,
    registering: Boolean,
    onToggle: () -> Unit,
    onRegister: (String) -> Unit,
) {
    Card(
        onClick = onToggle,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        offer.programName,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        offer.programCode +
                            (offer.regisToDate?.let { " | hạn đăng ký " + it } ?: ""),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (!offer.anyAvailable) {
                    ProgramChip("Hết suất", MaterialTheme.colorScheme.outline)
                }
            }

            offer.specification?.takeIf { it.isNotBlank() }?.let { spec ->
                Spacer(Modifier.height(6.dp))
                Text(
                    spec,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = if (expanded) 6 else 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (!expanded) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Chạm để chọn mức",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                return@Column
            }

            Spacer(Modifier.height(10.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            offer.levels.forEach { level ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(vertical = 8.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(level.levelName, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            level.requiredFaces.toString() + " mặt" +
                                (
                                    if (level.bonusAmount > 0) {
                                        " · thưởng " + formatDong(level.bonusAmount)
                                    } else {
                                        ""
                                    }
                                    ) +
                                // Only where a ceiling exists. "Còn 5 suất" on a
                                // level nobody is rationing would be a number the
                                // rep could not act on.
                                (level.slotsLeft?.let { " · còn " + it + " suất" } ?: ""),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    OutlinedButton(
                        onClick = { onRegister(level.levelId) },
                        enabled = level.available && canRegister && !registering,
                    ) { Text(if (level.available) "Đăng ký" else "Hết suất") }
                }
            }

            if (!canRegister) {
                Text(
                    // The registration belongs to the call it was agreed on, so
                    // outside one the list is readable and the button is not.
                    "Cần đang trong cuộc viếng thăm mới đăng ký được.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ProgramChip(text: String, tint: Color) {
    Surface(
        color = tint.copy(alpha = 0.12f),
        shape = RoundedCornerShape(6.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = tint,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
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

/**
 * A loyalty programme the outlet is in, and how far along the band it is.
 *
 * The bar measures against the band's floor, not its ceiling, because the floor
 * is what the reward turns on: an outlet at 4.9 of 5 million has earned nothing,
 * and a bar that reads nearly full is the honest picture of that.
 */
@Composable
private fun LoyaltyJoinedCard(program: LoyaltyProgram) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        program.programName,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        program.programCode + " | " + program.level.levelName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (program.isPending) {
                    ProgramChip("Chờ duyệt", MaterialTheme.colorScheme.tertiary)
                } else if (program.isMet) {
                    ProgramChip("Đã đạt", Color(0xFF2E7D32))
                } else {
                    ProgramChip("Đang tích", MaterialTheme.colorScheme.primary)
                }
            }

            Spacer(Modifier.height(10.dp))

            LinearProgressIndicator(
                progress = { program.progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = if (program.isMet) Color(0xFF2E7D32) else MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )

            Spacer(Modifier.height(6.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    loyaltyValue(program.achieved, program.countsBy) + " / " +
                        loyaltyValue(program.level.targetFrom, program.countsBy),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "Thưởng " + formatPercent(program.rewardPercent),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Text(
                if (program.isMet) {
                    "Đã đạt mức, thưởng tính cuối kỳ"
                } else {
                    "Còn thiếu " + loyaltyValue(program.remaining, program.countsBy)
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (program.isMet) {
                    Color(0xFF2E7D32)
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )

            Text(
                "Kỳ " + program.fromDate + " đến " + program.toDate,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LoyaltyOpenCard(
    offer: LoyaltyProgramOffer,
    expanded: Boolean,
    canRegister: Boolean,
    registering: Boolean,
    onToggle: () -> Unit,
    onRegister: (String) -> Unit,
) {
    Card(
        onClick = onToggle,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        offer.programName,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        offer.programCode +
                            (offer.regisToDate?.let { " | hạn đăng ký " + it } ?: ""),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (!offer.anyAvailable) {
                    ProgramChip("Hết suất", MaterialTheme.colorScheme.outline)
                }
            }

            offer.specification?.takeIf { it.isNotBlank() }?.let { spec ->
                Spacer(Modifier.height(6.dp))
                Text(
                    spec,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = if (expanded) 6 else 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (!expanded) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Chạm để chọn bậc",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                return@Column
            }

            Spacer(Modifier.height(10.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            offer.levels.forEach { level ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(vertical = 8.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(level.levelName, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            // The band, then what it pays, then what is left of the
                            // rep's allocation — only where a ceiling exists.
                            loyaltyBand(level, offer.countsBy) +
                                " · thưởng " +
                                formatPercent(level.rewardBasisPoints / 100.0) +
                                (level.slotsLeft?.let { " · còn " + it + " suất" } ?: ""),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    OutlinedButton(
                        onClick = { onRegister(level.levelId) },
                        enabled = level.available && canRegister && !registering,
                    ) { Text(if (level.available) "Đăng ký" else "Hết suất") }
                }
            }

            if (!canRegister) {
                Text(
                    "Cần đang trong cuộc viếng thăm mới đăng ký được.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * A band read out in the unit it is counted in.
 *
 * The top band has no ceiling — LevelTo null — and says "trở lên" rather than
 * showing an empty upper bound.
 */
private fun loyaltyBand(level: LoyaltyLevel, countsBy: LoyaltyCountsBy): String {
    val from = loyaltyValue(level.targetFrom, countsBy)
    val to = level.targetTo
    return if (to == null) "$from trở lên" else "$from - ${loyaltyValue(to, countsBy)}"
}

/** Money reads as money; a quantity programme counts units, not dong. */
private fun loyaltyValue(value: Long, countsBy: LoyaltyCountsBy): String =
    when (countsBy) {
        LoyaltyCountsBy.AMOUNT -> formatDong(value)
        LoyaltyCountsBy.QUANTITY -> "$value đơn vị"
    }

/** 2.5 rather than 2.50, and 3 rather than 3.0 — a percentage a rep reads aloud. */
private fun formatPercent(percent: Double): String {
    val whole = percent.toLong()
    return if (percent == whole.toDouble()) "$whole%" else "%.1f%%".format(percent)
}
