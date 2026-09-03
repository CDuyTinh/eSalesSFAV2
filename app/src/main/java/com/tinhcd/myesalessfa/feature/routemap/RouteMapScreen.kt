package com.tinhcd.myesalessfa.feature.routemap

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.rememberCameraPositionState
import com.tinhcd.myesalessfa.core.ui.ErrorBox
import com.tinhcd.myesalessfa.core.ui.LoadingBox
import com.tinhcd.myesalessfa.core.ui.theme.brand
import com.tinhcd.myesalessfa.domain.model.RouteStop
import com.tinhcd.myesalessfa.domain.model.VisitStatus
import kotlin.math.roundToInt

/**
 * Today's stops as pins.
 *
 * The question this answers is the one a list cannot: which of the shops still to
 * do is nearest, and are any of them worth folding into the trip I am already on.
 * So the pins carry the same colours the list card uses for status, and tapping
 * one shows what the rep needs to decide with — name, address, how far away, and
 * a way into the stop itself.
 */
@Composable
fun RouteMapScreen(
    onOpenStop: (RouteStop) -> Unit,
    onBack: () -> Unit,
    viewModel: RouteMapViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    RouteMapContent(
        state = state,
        onBack = onBack,
        onRetry = viewModel::load,
        onSelect = viewModel::select,
        onLocate = viewModel::locate,
        onOpenStop = onOpenStop,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RouteMapContent(
    state: RouteMapUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onSelect: (RouteStop?) -> Unit,
    onLocate: () -> Unit,
    onOpenStop: (RouteStop) -> Unit,
) {
    val context = LocalContext.current
    val cameraPositionState = rememberCameraPositionState()

    // Frame everything once the stops arrive, rather than opening on a default
    // position and leaving the rep to pinch their way to their own route.
    LaunchedEffect(state.stops) {
        val points = state.stops.mapNotNull { stop ->
            val lat = stop.customer.lat ?: return@mapNotNull null
            val lng = stop.customer.lng ?: return@mapNotNull null
            LatLng(lat, lng)
        }
        when {
            points.isEmpty() -> Unit
            // A single pin has no bounds to fit; fitting one would zoom to the
            // maximum and show a rooftop.
            points.size == 1 -> cameraPositionState.position =
                CameraPosition.fromLatLngZoom(points.first(), 15f)

            else -> {
                val bounds = LatLngBounds.builder().apply { points.forEach(::include) }.build()
                runCatching {
                    cameraPositionState.animate(
                        CameraUpdateFactory.newLatLngBounds(bounds, 120),
                    )
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Bản đồ tuyến") },
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
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                state.loading -> LoadingBox()
                state.error != null -> ErrorBox(state.error, onRetry = onRetry)
                state.stops.isEmpty() -> ErrorBox(
                    if (state.unmapped > 0) {
                        "Không có điểm bán nào trong tuyến hôm nay có toạ độ"
                    } else {
                        "Không có khách hàng nào trong tuyến hôm nay"
                    },
                )

                else -> {
                    GoogleMap(
                        modifier = Modifier.fillMaxSize(),
                        cameraPositionState = cameraPositionState,
                        // The blue dot needs the runtime permission, which the
                        // check-in flow has already asked for by the time anyone
                        // opens this. If it was refused the map still works.
                        properties = MapProperties(isMyLocationEnabled = state.me != null),
                        uiSettings = MapUiSettings(
                            zoomControlsEnabled = false,
                            myLocationButtonEnabled = false,
                        ),
                        onMapClick = { onSelect(null) },
                    ) {
                        state.stops.forEach { stop ->
                            val lat = stop.customer.lat ?: return@forEach
                            val lng = stop.customer.lng ?: return@forEach
                            Marker(
                                state = MarkerState(LatLng(lat, lng)),
                                title = stop.customer.name,
                                snippet = stop.customer.code,
                                icon = BitmapDescriptorFactory.defaultMarker(stop.hue()),
                                onClick = {
                                    onSelect(stop)
                                    // Let the map centre on it too, which is the
                                    // default behaviour worth keeping.
                                    false
                                },
                            )
                        }
                    }

                    if (state.unmapped > 0) {
                        UnmappedNotice(
                            count = state.unmapped,
                            modifier = Modifier.align(Alignment.TopCenter),
                        )
                    }

                    FloatingActionButton(
                        onClick = onLocate,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(16.dp),
                    ) { Icon(Icons.Default.MyLocation, contentDescription = "Vị trí của tôi") }

                    state.selected?.let { stop ->
                        StopSheet(
                            stop = stop,
                            distanceM = state.selectedDistanceM,
                            onOpen = { onOpenStop(stop) },
                            onNavigate = {
                                val lat = stop.customer.lat
                                val lng = stop.customer.lng
                                if (lat != null && lng != null) {
                                    // Handed to whatever maps app the rep uses for
                                    // turn-by-turn. Drawing a route here would mean
                                    // a directions API this app does not call.
                                    context.startActivity(
                                        Intent(
                                            Intent.ACTION_VIEW,
                                            Uri.parse(
                                                "geo:$lat,$lng?q=$lat,$lng(${stop.customer.name})",
                                            ),
                                        ),
                                    )
                                }
                            },
                            modifier = Modifier.align(Alignment.BottomCenter),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Says how many stops are not on the map.
 *
 * Without this the map is quietly incomplete: an outlet with no coordinates —
 * normal for one a rep registered in a spot with no fix — simply is not there,
 * and nothing on screen distinguishes that from a shorter route.
 */
@Composable
private fun UnmappedNotice(count: Int, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.secondary,
        contentColor = MaterialTheme.colorScheme.onSecondary,
        shape = RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Text(
            text = "$count điểm bán chưa có toạ độ, không hiện trên bản đồ",
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun StopSheet(
    stop: RouteStop,
    distanceM: Double?,
    onOpen: () -> Unit,
    onNavigate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(12.dp),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = stop.customer.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = listOfNotNull(stop.customer.code, stop.customer.address)
                    .joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = buildString {
                    append(stop.status.label())
                    // "Theo đường chim bay" because it is, and a rep planning the
                    // next hop should not be handed a straight line as if it were
                    // a drive.
                    distanceM?.let { append(" · cách ${it.roundToInt()} m theo đường chim bay") }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )

            Row(Modifier.padding(top = 8.dp)) {
                OutlinedButton(onClick = onNavigate, modifier = Modifier.weight(1f)) {
                    Text("Chỉ đường")
                }
                Spacer(Modifier.width(8.dp))
                androidx.compose.material3.Button(
                    onClick = onOpen,
                    enabled = stop.status.openable(),
                    modifier = Modifier.weight(1f),
                ) { Text(stop.status.actionLabel()) }
            }
        }
    }
}

/**
 * Pin colour by status, matching the chips on the list card.
 *
 * Google's stock pins only come in fixed hues, so these are the nearest ones to
 * the list's palette rather than the same values — close enough that a rep moving
 * between the two screens reads them the same way.
 */
private fun RouteStop.hue(): Float = when (status) {
    VisitStatus.PLANNED -> BitmapDescriptorFactory.HUE_AZURE
    VisitStatus.IN_PROGRESS -> BitmapDescriptorFactory.HUE_ORANGE
    VisitStatus.COMPLETED -> BitmapDescriptorFactory.HUE_GREEN
    VisitStatus.NO_ORDER -> BitmapDescriptorFactory.HUE_YELLOW
    VisitStatus.CLOSED -> BitmapDescriptorFactory.HUE_RED
    VisitStatus.ABANDONED -> BitmapDescriptorFactory.HUE_VIOLET
}

/**
 * Everything but a visit the server closed overnight, which belongs to a day the
 * rep can no longer act on. A finished stop is openable because a shop may be
 * called on again — the same rule the list card follows.
 */
private fun VisitStatus.openable(): Boolean = this != VisitStatus.ABANDONED

private fun VisitStatus.actionLabel(): String = when (this) {
    VisitStatus.IN_PROGRESS -> "Vào cuộc"
    VisitStatus.PLANNED -> "Check-in"
    else -> "Ghé lại"
}

private fun VisitStatus.label(): String = when (this) {
    VisitStatus.PLANNED -> "Chưa ghé"
    VisitStatus.IN_PROGRESS -> "Đang viếng thăm"
    VisitStatus.COMPLETED -> "Đã hoàn thành"
    VisitStatus.NO_ORDER -> "Không đặt hàng"
    VisitStatus.CLOSED -> "Đóng cửa"
    VisitStatus.ABANDONED -> "Bỏ dở - không check-out"
}
