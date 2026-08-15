package com.tinhcd.myesalessfa.feature.route

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tinhcd.myesalessfa.core.ui.ErrorBox
import com.tinhcd.myesalessfa.core.ui.LoadingBox
import com.tinhcd.myesalessfa.domain.model.RouteStop
import com.tinhcd.myesalessfa.domain.model.VisitStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RouteScreen(
    onOpenStop: (RouteStop) -> Unit,
    onSignedOut: () -> Unit,
    viewModel: RouteViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(state.signedOut) {
        if (state.signedOut) onSignedOut()
    }

    // Coming back from a check-in, the list must reflect it.
    LaunchedEffect(Unit) { viewModel.load() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Tuyen hom nay")
                        val subtitle = listOfNotNull(
                            state.me?.fullName,
                            state.me?.branchName,
                        ).joinToString(" - ")
                        if (subtitle.isNotBlank()) {
                            Text(subtitle, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::signOut) {
                        Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = "Dang xuat")
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding)) {
            when {
                state.loading -> LoadingBox()
                state.error != null -> ErrorBox(state.error.orEmpty(), onRetry = viewModel::load)
                else -> Column {
                    // Above the pending banner: this one blocks checking in at all,
                    // where that one only says work has yet to sync.
                    if (state.profileMissing) {
                        ProfileMissingBanner(
                            retrying = state.profileRetrying,
                            onRetry = viewModel::retryProfile,
                        )
                    }
                    if (state.pendingUploads > 0) {
                        PendingBanner(state.pendingUploads)
                    }
                    if (state.stops.isEmpty()) {
                        ErrorBox("Khong co khach hang nao trong tuyen hom nay")
                    } else {
                        LazyColumn(
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(state.stops, key = { it.customer.id }) { stop ->
                                StopCard(stop = stop, onClick = { onOpenStop(stop) })
                            }
                        }
                    }
                }
            }
        }
    }
}

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
                text = "Chua tai duoc thong tin nhan vien, chua the check-in",
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onRetry, enabled = !retrying) {
                Text(if (retrying) "Dang thu..." else "Thu lai")
            }
        }
    }
}

@Composable
private fun PendingBanner(count: Int) {
    Surface(
        color = MaterialTheme.colorScheme.secondary,
        contentColor = MaterialTheme.colorScheme.onSecondary,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = "$count ban ghi cho dong bo",
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun StopCard(stop: RouteStop, onClick: () -> Unit) {
    val done = stop.status == VisitStatus.COMPLETED

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !done, onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OrderBadge(stop.visitOrder, done)

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp),
            ) {
                Text(
                    text = stop.customer.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stop.customer.code +
                        (stop.customer.address?.let { " - $it" } ?: ""),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stop.status.label(),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (done) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }

            if (done) {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun OrderBadge(order: Int, done: Boolean) {
    Surface(
        shape = CircleShape,
        color = if (done) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.primaryContainer
        },
        modifier = Modifier.size(36.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = order.toString(),
                style = MaterialTheme.typography.bodyLarge,
                color = if (done) Color.White else MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

private fun VisitStatus.label(): String = when (this) {
    VisitStatus.PLANNED -> "Chua ghe"
    VisitStatus.IN_PROGRESS -> "Dang trong cuoc vieng tham"
    VisitStatus.COMPLETED -> "Da hoan thanh"
    VisitStatus.NO_ORDER -> "Khong dat hang"
    VisitStatus.CLOSED -> "Dong cua"
    // Defensive only: abandonment applies to earlier dates than the one asked for,
    // and this list is one day's stops, so a stop should never arrive in this state.
    VisitStatus.ABANDONED -> "Bo do - khong check-out"
}
