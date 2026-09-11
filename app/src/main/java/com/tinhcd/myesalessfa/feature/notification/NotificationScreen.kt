package com.tinhcd.myesalessfa.feature.notification

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.StickyNote2
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.LocalOffer
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material.icons.filled.ViewCarousel
import androidx.compose.material.icons.filled.Workspaces
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
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
import androidx.compose.ui.graphics.vector.ImageVector
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
import com.tinhcd.myesalessfa.domain.model.Notification
import com.tinhcd.myesalessfa.domain.model.NotificationFeed
import com.tinhcd.myesalessfa.domain.model.NotificationKind

/**
 * Thông báo.
 *
 * Everything head office has published that is live for this rep, plus their own
 * outstanding notes. Tapping an item reads it; the filters narrow by kind and by
 * whether it has been read, which are the legacy screen's own two filters.
 */
@Composable
fun NotificationScreen(
    onBack: () -> Unit,
    viewModel: NotificationViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    NotificationContent(
        state = state,
        onBack = onBack,
        onRetry = viewModel::load,
        onFilter = viewModel::onFilter,
        onUnreadOnly = viewModel::onUnreadOnly,
        onOpen = viewModel::onOpen,
        onMarkAll = viewModel::onMarkAll,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NotificationContent(
    state: NotificationUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onFilter: (NotificationKind?) -> Unit,
    onUnreadOnly: (Boolean) -> Unit,
    onOpen: (Notification) -> Unit,
    onMarkAll: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Thông báo")
                        if (state.feed.unread > 0) {
                            Text(
                                "${state.feed.unread} chưa đọc",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Quay lại")
                    }
                },
                actions = {
                    IconButton(onClick = onMarkAll, enabled = state.canMarkAll) {
                        Icon(Icons.Default.DoneAll, contentDescription = "Đánh dấu đã đọc hết")
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
                state.error != null && state.feed.items.isEmpty() ->
                    ErrorBox(state.error, onRetry = onRetry)

                else -> Column(Modifier.fillMaxSize()) {
                    FilterRow(
                        feed = state.feed,
                        selected = state.filter,
                        unreadOnly = state.unreadOnly,
                        onFilter = onFilter,
                        onUnreadOnly = onUnreadOnly,
                    )

                    if (state.visible.isEmpty()) {
                        EmptyFeed(unreadOnly = state.unreadOnly)
                    } else {
                        LazyColumn(
                            contentPadding = PaddingValues(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(state.visible, key = { it.key }) { item ->
                                NotificationCard(item = item, onClick = { onOpen(item) })
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * The kind filter, plus the unread switch on the end.
 *
 * Only kinds actually present get a chip: a filter for a category with nothing
 * in it is a button that does nothing, and the legacy's fixed four-entry filter
 * list had exactly that problem.
 */
@Composable
private fun FilterRow(
    feed: NotificationFeed,
    selected: NotificationKind?,
    unreadOnly: Boolean,
    onFilter: (NotificationKind?) -> Unit,
    onUnreadOnly: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = selected == null,
            onClick = { onFilter(null) },
            label = { Text("Tất cả") },
        )

        feed.presentKinds.forEach { kind ->
            FilterChip(
                selected = selected == kind,
                onClick = { onFilter(kind) },
                label = { Text(kind.label) },
            )
        }

        FilterChip(
            selected = unreadOnly,
            onClick = { onUnreadOnly(!unreadOnly) },
            label = { Text("Chưa đọc") },
            colors = FilterChipDefaults.filterChipColors(
                selectedContainerColor = MaterialTheme.colorScheme.errorContainer,
                selectedLabelColor = MaterialTheme.colorScheme.onErrorContainer,
            ),
        )
    }
}

@Composable
private fun NotificationCard(item: Notification, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.size(40.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        iconFor(item.kind),
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }

            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Unread stands out by weight and by a dot, not by colour
                    // alone: this list is read in a doorway in daylight.
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = if (item.isRead) FontWeight.Normal else FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (!item.isRead) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(8.dp),
                        ) {}
                    }
                }

                Text(
                    text = item.body,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )

                Text(
                    text = subtitle(item),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}

/** The kind, the code if there is one, and how long it runs. */
private fun subtitle(item: Notification): String = buildString {
    append(item.kind.label)
    item.code?.let { append(" · ").append(it) }

    val from = item.fromDate?.let(::shortDate)
    val to = item.toDate?.let(::shortDate)
    when {
        from != null && to != null && from != to ->
            append(" · ").append(from).append(" - ").append(to)

        from != null -> append(" · ").append(from)
        // An open-ended campaign says nothing rather than showing half a range.
        to != null -> append(" · đến ").append(to)
        else -> Unit
    }
}

/** `2026-09-11` as `11/09`. The year is noise on a list of live campaigns. */
private fun shortDate(iso: String): String =
    if (iso.length >= 10) "${iso.substring(8, 10)}/${iso.substring(5, 7)}" else iso

private fun iconFor(kind: NotificationKind): ImageVector = when (kind) {
    NotificationKind.PROMOTION -> Icons.Default.LocalOffer
    NotificationKind.MANUAL_PROMOTION -> Icons.Default.CardGiftcard
    NotificationKind.DISPLAY -> Icons.Default.ViewCarousel
    NotificationKind.LOYALTY -> Icons.Default.Workspaces
    NotificationKind.POSM -> Icons.Default.Storefront
    NotificationKind.WORK_NOTE -> Icons.AutoMirrored.Filled.StickyNote2
}

@Composable
private fun EmptyFeed(unreadOnly: Boolean) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Default.NotificationsNone,
            contentDescription = null,
            modifier = Modifier.size(40.dp),
            tint = MaterialTheme.colorScheme.outline,
        )
        Text(
            text = if (unreadOnly) "Đã đọc hết" else "Chưa có thông báo nào",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
    }
}

// -----------------------------------------------------------------------------

private val SampleFeed = NotificationFeed(
    items = listOf(
        Notification(
            kind = NotificationKind.PROMOTION,
            sourceId = "1",
            title = "Nước giải khát - chiết khấu theo nhóm",
            body = "Chương trình khuyến mãi đang chạy",
            code = "KM-NGK",
            fromDate = "2026-08-22",
            toDate = "2026-12-05",
        ),
        Notification(
            kind = NotificationKind.DISPLAY,
            sourceId = "2",
            title = "Trưng bày nước giải khát quý 3",
            body = "Chương trình trưng bày - cần đăng ký",
            code = "TB2609",
            fromDate = "2026-08-06",
            toDate = "2026-11-04",
        ),
        Notification(
            kind = NotificationKind.WORK_NOTE,
            sourceId = "3",
            title = "Nhắc gọi lại NPP",
            body = "Hỏi lại lịch giao hàng tuần sau",
            fromDate = "2026-09-11",
            toDate = "2026-09-11",
            isRead = true,
        ),
    ),
    unread = 2,
)

@Preview(name = "Thông báo", showBackground = true, heightDp = 700)
@Composable
private fun NotificationPreview() {
    MyeSalesTheme {
        NotificationContent(
            state = NotificationUiState(loading = false, feed = SampleFeed),
            onBack = {},
            onRetry = {},
            onFilter = {},
            onUnreadOnly = {},
            onOpen = {},
            onMarkAll = {},
        )
    }
}

@Preview(name = "Thông báo - đã đọc hết", showBackground = true, heightDp = 700)
@Composable
private fun NotificationEmptyPreview() {
    MyeSalesTheme {
        NotificationContent(
            state = NotificationUiState(
                loading = false,
                unreadOnly = true,
                feed = SampleFeed.withAllRead(),
            ),
            onBack = {},
            onRetry = {},
            onFilter = {},
            onUnreadOnly = {},
            onOpen = {},
            onMarkAll = {},
        )
    }
}
