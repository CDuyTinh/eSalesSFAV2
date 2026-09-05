package com.tinhcd.myesalessfa.feature.route

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.zIndex
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.tinhcd.myesalessfa.core.ui.ErrorBox
import com.tinhcd.myesalessfa.core.ui.LoadingBox
import com.tinhcd.myesalessfa.core.ui.SearchBox
import com.tinhcd.myesalessfa.core.ui.theme.MyeSalesTheme
import com.tinhcd.myesalessfa.core.ui.theme.brand
import com.tinhcd.myesalessfa.domain.model.Customer
import com.tinhcd.myesalessfa.domain.model.RouteStop
import com.tinhcd.myesalessfa.domain.model.SyncState
import com.tinhcd.myesalessfa.domain.model.VisitStatus
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The Viếng thăm tab: today's stops, in the shape the app this replaces gave them.
 *
 * The layout is deliberate rather than incidental. A rep works this screen standing
 * in a shop doorway, so the identifying details — shop front, name, code, phone,
 * address — sit together at the top of each card, and the one action that stop is
 * currently waiting for sits alone on the bottom row where a thumb reaches it. The
 * header carries its own search and filter instead of a plain app bar because a
 * fifty-stop route is not something anyone should have to scroll to search.
 */
@Composable
fun RouteScreen(
    onOpenStop: (RouteStop) -> Unit,
    onOpenCustomer: (RouteStop) -> Unit,
    onOpenMap: () -> Unit,
    onOpenDrawer: () -> Unit,
    /**
     * Head office may have added a tab in the same refresh that brought new
     * prices, and the shell has to re-read its menu to show it.
     */
    onReferenceDataRefreshed: () -> Unit = {},
    viewModel: RouteViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Coming back from a check-in, the list must reflect it.
    LaunchedEffect(Unit) { viewModel.load() }

    LaunchedEffect(state.sync.syncing) {
        if (!state.sync.syncing) onReferenceDataRefreshed()
    }

    RouteContent(
        state = state,
        onOpenStop = onOpenStop,
        onOpenCustomer = onOpenCustomer,
        onOpenMap = onOpenMap,
        onOpenDrawer = onOpenDrawer,
        onRetry = viewModel::load,
        onRetryProfile = viewModel::retryProfile,
        onRefreshReferenceData = viewModel::refreshReferenceData,
        onQueryChanged = viewModel::onQueryChanged,
        onFilterChanged = viewModel::onFilterChanged,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RouteContent(
    state: RouteUiState,
    onOpenStop: (RouteStop) -> Unit,
    onOpenCustomer: (RouteStop) -> Unit,
    onOpenMap: () -> Unit,
    onOpenDrawer: () -> Unit,
    onRetry: () -> Unit,
    onRetryProfile: () -> Unit,
    onRefreshReferenceData: () -> Unit,
    onQueryChanged: (String) -> Unit,
    onFilterChanged: (RouteFilter) -> Unit,
) {
    var filterSheetOpen by remember { mutableStateOf(false) }
    val context = LocalContext.current

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        RouteHeader(
            query = state.query,
            onQueryChanged = onQueryChanged,
            syncing = state.sync.syncing,
            filtering = state.filtering,
            onOpenDrawer = onOpenDrawer,
            onRefresh = onRefreshReferenceData,
            onOpenFilter = { filterSheetOpen = true },
        )

        // First, because it is the only one that stops the rep working: a check-in
        // stamps ids from the profile and is refused without it.
        if (state.profileMissing) {
            ProfileMissingBanner(retrying = state.profileRetrying, onRetry = onRetryProfile)
        }
        if (state.sync.lastAttemptFailed && !state.sync.syncing) {
            Text(
                "Chưa cập nhật được dữ liệu mới nhất. Đang dùng dữ liệu đã tải trước đó.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }

        Box(Modifier.weight(1f)) {
            // Only once there is something to plot. A map button over an empty
            // route opens an empty map, which answers nothing.
            if (state.stops.isNotEmpty()) {
                FloatingActionButton(
                    onClick = onOpenMap,
                    containerColor = MapRed,
                    contentColor = Color.White,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(16.dp)
                        .zIndex(1f),
                ) {
                    Icon(Icons.Default.Map, contentDescription = "Xem tuyến trên bản đồ")
                }
            }

            when {
                state.loading && state.stops.isEmpty() -> LoadingBox()
                state.error != null -> ErrorBox(state.error, onRetry = onRetry)
                state.stops.isEmpty() ->
                    ErrorBox("Không có khách hàng nào trong tuyến hôm nay")

                else -> StopList(
                    stops = state.visibleStops,
                    filtering = state.filtering,
                    filter = state.filter,
                    subtitle = listOfNotNull(state.me?.fullName, state.me?.branchName)
                        .joinToString(" - "),
                    onOpenStop = onOpenStop,
                    onOpenCustomer = onOpenCustomer,
                    onCall = { phone ->
                        context.startActivity(
                            Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone")),
                        )
                    },
                    // From the whole route, not the filtered list: a rep who has
                    // searched for the next shop must still be told the previous
                    // one is open, and it would have scrolled out of the results.
                    openStop = state.openStop,
                )
            }
        }
    }

    if (filterSheetOpen) {
        ModalBottomSheet(
            onDismissRequest = { filterSheetOpen = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            FilterSheet(
                selected = state.filter,
                onSelect = {
                    onFilterChanged(it)
                    filterSheetOpen = false
                },
            )
        }
    }
}

// -----------------------------------------------------------------------------
// Header
// -----------------------------------------------------------------------------

/**
 * The brand band, carrying the title, the day's controls and the search box.
 *
 * Rounded only at the bottom, so the list below reads as sliding under it rather
 * than starting after it — the same trick the legacy screen used, and the reason
 * the first card is worth its 16dp of air.
 */
@Composable
private fun RouteHeader(
    query: String,
    onQueryChanged: (String) -> Unit,
    syncing: Boolean,
    filtering: Boolean,
    onOpenDrawer: () -> Unit,
    onRefresh: () -> Unit,
    onOpenFilter: () -> Unit,
) {
    val brand = MaterialTheme.brand
    Box(
        Modifier
            .fillMaxWidth()
            .background(
                brush = Brush.linearGradient(
                    listOf(brand.header, lerp(brand.header, Color.White, 0.22f)),
                ),
                shape = RoundedCornerShape(bottomStart = 20.dp, bottomEnd = 20.dp),
            ),
    ) {
        Column(
            Modifier
                .statusBarsPadding()
                .padding(horizontal = 8.dp)
                .padding(bottom = 16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onOpenDrawer) {
                    Icon(Icons.Default.Menu, contentDescription = "Mở menu", tint = brand.onHeader)
                }
                Text(
                    text = "Viếng thăm",
                    style = MaterialTheme.typography.titleLarge,
                    fontSize = 18.sp,
                    color = brand.onHeader,
                    modifier = Modifier.weight(1f),
                )

                // The escape hatch for "head office says they changed it". The
                // spinner replaces the button rather than sitting beside it, so a
                // second tap cannot start a second fetch.
                if (syncing) {
                    CircularProgressIndicator(
                        strokeWidth = 2.dp,
                        color = brand.onHeader,
                        modifier = Modifier
                            .padding(horizontal = 12.dp)
                            .size(20.dp),
                    )
                } else {
                    IconButton(onClick = onRefresh) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = "Cập nhật dữ liệu",
                            tint = brand.onHeader,
                        )
                    }
                }

                IconButton(onClick = onOpenFilter) {
                    Box(contentAlignment = Alignment.TopEnd) {
                        Icon(
                            Icons.Default.Tune,
                            contentDescription = "Lọc danh sách",
                            tint = brand.onHeader,
                        )
                        // A filtered list looks exactly like a short route, so the
                        // control that shortened it has to say so.
                        if (filtering) {
                            Box(
                                Modifier
                                    .size(8.dp)
                                    .background(FilterActive, CircleShape),
                            )
                        }
                    }
                }
            }

            SearchBox(
                query = query,
                onQueryChanged = onQueryChanged,
                placeholder = "Tìm tên, mã khách hàng, địa chỉ",
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}

// -----------------------------------------------------------------------------
// List
// -----------------------------------------------------------------------------

@Composable
private fun StopList(
    stops: List<RouteStop>,
    filtering: Boolean,
    filter: RouteFilter,
    subtitle: String,
    onOpenStop: (RouteStop) -> Unit,
    onOpenCustomer: (RouteStop) -> Unit,
    onCall: (String) -> Unit,
    openStop: RouteStop?,
) {
    Column(Modifier.fillMaxSize()) {
        // Above the heading and outside the list, so it cannot scroll away. The
        // greyed check-in chips below it are otherwise a dead end: the rep can
        // see they may not check in and nothing tells them why or what to do.
        if (openStop != null) {
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 12.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    Icon(
                        Icons.Default.Schedule,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Đang viếng thăm ${openStop.customer.name}. " +
                            "Check-out trước khi ghé điểm khác.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
        }

        // The heading stays put while the cards move under it: it counts what is on
        // screen, and a count that scrolls away is a count nobody can check against.
        ListHeading(
            subtitle = if (filtering) filter.label else subtitle,
            count = stops.size,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
        )

        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (stops.isEmpty()) {
                item {
                    Text(
                        text = "Không có khách hàng nào khớp với điều kiện lọc",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp),
                    )
                }
            }

            items(stops, key = { it.customer.id }) { stop ->
                StopCard(
                    stop = stop,
                    onOpen = { onOpenStop(stop) },
                    onOpenHub = { onOpenCustomer(stop) },
                    checkInBlocked = openStop != null && openStop.customer.id != stop.customer.id,
                    onCall = onCall,
                )
            }
        }
    }
}

/** "Tuyến hôm nay — N khách hàng", the one line that frames everything under it. */
@Composable
private fun ListHeading(subtitle: String, count: Int, modifier: Modifier = Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f)) {
            Text(
                text = "Tuyến hôm nay",
                style = MaterialTheme.typography.titleLarge,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
            )
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        Text(
            text = "$count khách hàng",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// -----------------------------------------------------------------------------
// Card
// -----------------------------------------------------------------------------

/**
 * Two ways in, and they mean different things.
 *
 * The card opens the outlet's own screen — details, order history, the visit's
 * work if one is open. Reading a shop's credit limit should not leave a
 * timestamped record saying the rep was there, so this commits to nothing.
 *
 * The chip is the commitment: check in, or step back into the call in progress.
 * It stays on the card because that is the thing a rep taps forty times a day.
 */
@Composable
private fun StopCard(
    stop: RouteStop,
    onOpen: () -> Unit,
    onOpenHub: () -> Unit,
    checkInBlocked: Boolean,
    onCall: (String) -> Unit,
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier
            .fillMaxWidth()
            // Always enabled, unlike the chip. A finished stop, or one where the
            // outlet was shut, is exactly when a rep goes looking for the phone
            // number or what the shop last took.
            .clickable(onClick = onOpenHub),
    ) {
        Column {
            Row(Modifier.padding(12.dp)) {
                StoreAvatar(url = stop.customer.avatarUrl, order = stop.visitOrder)

                Spacer(Modifier.width(12.dp))

                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.Top) {
                        Text(
                            text = stop.customer.name,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        // Before the code, because it changes what the code means:
                        // this outlet is provisional and could still be rejected.
                        if (stop.unplanned) {
                            CodeChip(code = "Mới", color = UnplannedViolet)
                            Spacer(Modifier.width(4.dp))
                        }
                        CodeChip(code = stop.customer.code, color = stop.status.chipColor())
                    }

                    Spacer(Modifier.height(6.dp))
                    InfoRow(
                        icon = Icons.Default.Phone,
                        text = stop.customer.phone ?: "Chưa có số điện thoại",
                    )
                    InfoRow(
                        icon = Icons.Default.LocationOn,
                        text = stop.customer.address ?: "Chưa có địa chỉ",
                    )
                    InfoRow(
                        icon = Icons.Default.Schedule,
                        // The status describes the latest call. Once there has
                        // been more than one, saying only that would quietly
                        // drop the earlier ones from the day they happened on.
                        text = if (stop.visitCount > 1) {
                            "${stop.status.label()} - đã ghé ${stop.visitCount} lần"
                        } else {
                            stop.status.label()
                        },
                    )
                }
            }

            HorizontalDivider()

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 12.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
            ) {
                // The times, not a duration bar: a rep asked about a stop repeats the
                // clock times back, because that is what the shop remembers too.
                Text(
                    text = stop.visitTimes(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.End,
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 8.dp),
                )

                StopAction(
                    status = stop.status,
                    checkInBlocked = checkInBlocked,
                    onOpen = onOpen,
                )

                val phone = stop.customer.phone
                IconButton(
                    onClick = { phone?.let(onCall) },
                    enabled = !phone.isNullOrBlank(),
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        Icons.Default.Phone,
                        contentDescription = "Gọi ${stop.customer.name}",
                        tint = CallBlue,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

/**
 * The shop front, with the stop's position on the route pinned to it.
 *
 * The number is on the photo rather than beside the name because it answers a
 * different question — where am I in the day — and putting it in the text column
 * made the name the second thing read instead of the first.
 */
@Composable
private fun StoreAvatar(url: String?, order: Int) {
    Box {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.size(72.dp),
        ) {
            if (url.isNullOrBlank()) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.Storefront,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(28.dp),
                    )
                }
            } else {
                AsyncImage(
                    model = url,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        Surface(
            color = MaterialTheme.colorScheme.primary,
            contentColor = Color.White,
            shape = RoundedCornerShape(topEnd = 8.dp, bottomStart = 12.dp),
            modifier = Modifier.align(Alignment.BottomStart),
        ) {
            Text(
                text = order.toString(),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
    }
}

@Composable
private fun CodeChip(code: String, color: Color) {
    Surface(
        color = color,
        contentColor = Color.White,
        shape = RoundedCornerShape(12.dp),
    ) {
        Text(
            text = code,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun InfoRow(icon: ImageVector, text: String) {
    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier.padding(top = 4.dp),
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .padding(top = 2.dp)
                .size(14.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The one thing this stop is waiting for, or a statement that it is not waiting.
 *
 * A finished stop offers the call again rather than a label. The owner was out,
 * the shelf count needs redoing, the order is agreed on a second pass — all of
 * that is an ordinary day, and the card used to end it. Nothing is lost by
 * dropping the label: the status is already on the line above and in the colour
 * of the code chip.
 *
 * Only an abandoned visit still gets a label, because it belongs to a day the
 * rep can no longer act on.
 */
@Composable
private fun StopAction(status: VisitStatus, checkInBlocked: Boolean, onOpen: () -> Unit) {
    when (status) {
        // Grey and inert while another shop is open, the way the legacy card
        // greys it. The banner above the list says which shop and what to do
        // about it — a dead chip on its own would leave the rep pressing.
        VisitStatus.PLANNED -> ActionChip(
            text = "Check-in",
            container = if (checkInBlocked) StatusGrey else MaterialTheme.colorScheme.primary,
            content = Color.White,
            enabled = !checkInBlocked,
            onClick = onOpen,
        )

        VisitStatus.IN_PROGRESS -> ActionChip(
            text = "Vào cuộc viếng thăm",
            container = ActionRed,
            content = Color.White,
            icon = Icons.AutoMirrored.Filled.ArrowForward,
            onClick = onOpen,
        )

        // Deliberately quieter than a first check-in. A rep scanning the list is
        // looking for the shops they have not reached yet, and a second call on
        // a finished one should not pull the eye away from them.
        VisitStatus.COMPLETED, VisitStatus.NO_ORDER, VisitStatus.CLOSED -> ActionChip(
            text = "Ghé lại",
            container = if (checkInBlocked) {
                StatusGrey
            } else {
                MaterialTheme.colorScheme.secondaryContainer
            },
            content = if (checkInBlocked) {
                Color.White
            } else {
                MaterialTheme.colorScheme.onSecondaryContainer
            },
            enabled = !checkInBlocked,
            onClick = onOpen,
        )

        VisitStatus.ABANDONED -> StatusLabel(text = status.label(), color = StatusGrey)
    }
}

@Composable
private fun ActionChip(
    text: String,
    container: Color,
    content: Color,
    onClick: () -> Unit,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    Surface(
        color = container,
        contentColor = content,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .padding(end = 4.dp)
            .clickable(enabled = enabled, onClick = onClick),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
            }
            Text(text = text, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun StatusLabel(
    text: String,
    color: Color,
    icon: ImageVector? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(end = 4.dp, start = 4.dp),
    ) {
        if (icon != null) {
            Icon(
                icon,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(4.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
        )
    }
}

// -----------------------------------------------------------------------------
// Filter sheet
// -----------------------------------------------------------------------------

@Composable
private fun FilterSheet(selected: RouteFilter, onSelect: (RouteFilter) -> Unit) {
    Column(Modifier.padding(bottom = 24.dp)) {
        Text(
            text = "Lọc danh sách",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 12.dp),
        )
        HorizontalDivider()

        RouteFilter.entries.forEach { option ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(option) }
                    .padding(horizontal = 20.dp, vertical = 16.dp),
            ) {
                Text(
                    text = option.label,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                if (option == selected) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

// -----------------------------------------------------------------------------
// Banner
// -----------------------------------------------------------------------------

/**
 * Says that the session is fine but the rep's details are missing, and offers the
 * one action that helps.
 *
 * The rep is deliberately left on this screen rather than bounced to login: the
 * session is valid, and signing in again would neither be necessary nor fix a
 * failed request. Error colours because a check-in will be refused until it clears.
 */
@Composable
private fun ProfileMissingBanner(retrying: Boolean, onRetry: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Chưa tải được thông tin nhân viên, chưa thể check-in",
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onRetry, enabled = !retrying) {
                Text(if (retrying) "Đang thử..." else "Thử lại")
            }
        }
    }
}

// -----------------------------------------------------------------------------
// Status vocabulary
// -----------------------------------------------------------------------------

/**
 * Fixed rather than theme-derived, and carried over from the app this replaces.
 *
 * These colours are read as a set — green means finished, amber means started,
 * grey means untouched, red means shut — so they have to stay distinguishable from
 * each other rather than from whatever surface they land on, which is what would
 * happen if dark mode pulled them all toward one value.
 */
private val StatusGreen = Color(0xFF04A489)
private val StatusAmber = Color(0xFFF5A202)
private val StatusGrey = Color(0xFF8A8A93)
private val ActionRed = Color(0xFFD5262B)
private val CallBlue = Color(0xFF2D2DFE)

/** Not one of the status colours: being off-MCP is not a stage of a visit. */
private val UnplannedViolet = Color(0xFF5C00D4)

/** The map button, in the red the app this replaces used for it. */
private val MapRed = Color(0xFFD5262B)
private val FilterActive = Color(0xFFF5A202)

private fun VisitStatus.chipColor(): Color = when (this) {
    VisitStatus.PLANNED -> StatusGrey
    VisitStatus.IN_PROGRESS -> StatusAmber
    VisitStatus.COMPLETED -> StatusGreen
    VisitStatus.NO_ORDER -> StatusAmber
    VisitStatus.CLOSED -> ActionRed
    VisitStatus.ABANDONED -> StatusGrey
}

private fun VisitStatus.label(): String = when (this) {
    VisitStatus.PLANNED -> "Chưa ghé"
    VisitStatus.IN_PROGRESS -> "Đang trong cuộc viếng thăm"
    VisitStatus.COMPLETED -> "Đã hoàn thành"
    VisitStatus.NO_ORDER -> "Không đặt hàng"
    VisitStatus.CLOSED -> "Đóng cửa"
    // Defensive only: abandonment applies to earlier dates than the one asked for,
    // and this list is one day's stops, so a stop should never arrive in this state.
    VisitStatus.ABANDONED -> "Bỏ dở - không check-out"
}


/** Wall-clock time, which is what a rep compares against their own watch. */
private val ClockFormat = DateTimeFormatter.ofPattern("HH:mm")

private fun clockOf(epochMs: Long): String = ClockFormat
    .format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))

private fun RouteStop.visitTimes(): String {
    val start = checkInAtEpochMs ?: return ""
    val end = checkOutAtEpochMs
        ?: return "Vào lúc ${clockOf(start)}"
    val minutes = Duration.ofMillis(end - start).toMinutes()
    return "${clockOf(start)} - ${clockOf(end)} - $minutes phút"
}

// -----------------------------------------------------------------------------
// Previews
// -----------------------------------------------------------------------------

private fun sampleCustomer(
    id: String,
    code: String,
    name: String,
    phone: String? = "0901 234 567",
    address: String? = "12 Nguyễn Văn Cừ, Phường 4, Quận 5",
) = Customer(
    id = id,
    code = code,
    name = name,
    address = address,
    phone = phone,
    lat = null,
    lng = null,
    avatarUrl = null,
    checkInRadiusM = null,
)

private val SampleStops = listOf(
    RouteStop(
        customer = sampleCustomer("1", "KH0012", "Tạp hoá Bà Bảy"),
        visitOrder = 1,
        status = VisitStatus.COMPLETED,
        visitId = "v1",
        checkInAtEpochMs = 1_755_400_000_000,
        checkOutAtEpochMs = 1_755_401_680_000,
    ),
    RouteStop(
        customer = sampleCustomer("2", "KH0013", "Cửa hàng tiện lợi Minh Anh"),
        visitOrder = 2,
        status = VisitStatus.IN_PROGRESS,
        visitId = "v2",
        checkInAtEpochMs = 1_755_403_000_000,
        checkOutAtEpochMs = null,
    ),
    RouteStop(
        customer = sampleCustomer("3", "KH0014", "Siêu thị mini Hoa Sen", phone = null),
        visitOrder = 3,
        status = VisitStatus.PLANNED,
        visitId = null,
        checkInAtEpochMs = null,
        checkOutAtEpochMs = null,
    ),
    RouteStop(
        customer = sampleCustomer("4", "KH0015", "Quán tạp hoá Cô Tư"),
        visitOrder = 4,
        status = VisitStatus.CLOSED,
        visitId = "v4",
        checkInAtEpochMs = 1_755_404_000_000,
        checkOutAtEpochMs = 1_755_404_300_000,
    ),
)

@Preview(name = "Viếng thăm", showBackground = true, heightDp = 900)
@Composable
private fun RoutePreview() {
    MyeSalesTheme {
        RouteContent(
            state = RouteUiState(loading = false, stops = SampleStops, sync = SyncState()),
            onOpenStop = {},
            onOpenCustomer = {},
            onOpenMap = {},
            onOpenDrawer = {},
            onRetry = {},
            onRetryProfile = {},
            onRefreshReferenceData = {},
            onQueryChanged = {},
            onFilterChanged = {},
        )
    }
}

@Preview(name = "Viếng thăm - đang lọc", showBackground = true, heightDp = 900)
@Composable
private fun RouteFilteredPreview() {
    MyeSalesTheme {
        RouteContent(
            state = RouteUiState(
                loading = false,
                stops = SampleStops,
                filter = RouteFilter.PLANNED,
                query = "không khớp",
            ),
            onOpenStop = {},
            onOpenCustomer = {},
            onOpenMap = {},
            onOpenDrawer = {},
            onRetry = {},
            onRetryProfile = {},
            onRefreshReferenceData = {},
            onQueryChanged = {},
            onFilterChanged = {},
        )
    }
}
