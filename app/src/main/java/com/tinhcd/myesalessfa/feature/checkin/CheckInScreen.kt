package com.tinhcd.myesalessfa.feature.checkin

import android.Manifest
import com.tinhcd.myesalessfa.R
import androidx.compose.ui.res.stringResource
import com.tinhcd.myesalessfa.core.R as CoreR
import com.tinhcd.myesalessfa.core.permission.rememberPermissionRequest
import com.tinhcd.myesalessfa.feature.nfc.NfcState
import com.tinhcd.myesalessfa.feature.nfc.NfcTagReader
import com.tinhcd.myesalessfa.feature.nfc.nfcState
import com.tinhcd.myesalessfa.core.ui.theme.status
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.tinhcd.myesalessfa.feature.photo.DualCameraCapture
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tinhcd.myesalessfa.core.ui.ErrorBox
import com.tinhcd.myesalessfa.core.ui.SkeletonDetailCard
import com.tinhcd.myesalessfa.core.ui.PrimaryButton
import com.tinhcd.myesalessfa.core.ui.theme.MyeSalesTheme
import com.tinhcd.myesalessfa.core.ui.theme.brand
import com.tinhcd.myesalessfa.domain.model.CheckInGate
import com.tinhcd.myesalessfa.domain.model.Customer
import com.tinhcd.myesalessfa.domain.model.ReasonCode
import com.tinhcd.myesalessfa.domain.model.ReasonKind
import coil3.compose.AsyncImage
import java.io.File
import kotlin.math.roundToInt

/**
 * Starting a visit, laid out the way the app this replaces lays it out: a
 * coloured band carrying the shop's name, then one white card of labelled rows,
 * then the button that commits.
 *
 * The card is the part worth copying. Everything the rep has to check before
 * they press the button — how far away they are, why they are checking in from
 * here, anything they want on the record — reads down one column of labels with
 * the answers ranged right, so a glance confirms it rather than a read.
 */
@Composable
fun CheckInScreen(
    onDone: () -> Unit,
    viewModel: CheckInViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Approximate location is enough to be asked for a reason; refused, the
    // check-in still goes ahead on the GPS-unavailable path.
    val locationPermission = rememberPermissionRequest(
        listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
        stringResource(CoreR.string.permission_rationale_location),
        anyOf = true,
        onDenied = viewModel::refreshLocation,
    )

    // Ask once on entry. The check-in rules need a fix before they can say
    // anything useful.
    LaunchedEffect(Unit) { locationPermission.launch(viewModel::refreshLocation) }

    val cameraPermission = rememberPermissionRequest(
        listOf(Manifest.permission.CAMERA),
        stringResource(CoreR.string.permission_rationale_camera),
    )
    val capturePhoto = { cameraPermission.launch(viewModel::openCamera) }

    if (state.cameraOpen) {
        DualCameraCapture(
            newFile = viewModel::newPhotoFile,
            onDone = viewModel::onPhotosTaken,
            onCancel = viewModel::closeCamera,
        )
    }

    // The shop's tag, read whenever it is tapped while this screen is open.
    NfcTagReader(onTag = viewModel::onNfcTag)

    LaunchedEffect(state.finished) {
        if (state.finished) onDone()
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        CheckInHeader(customer = state.customer, onClose = onDone)

        when {
            state.loading -> SkeletonDetailCard(rows = 4)
            state.customer == null -> ErrorBox(
                state.error?.asString() ?: stringResource(R.string.checkin_not_on_route),
            )

            else -> Column(Modifier.fillMaxSize()) {
                Column(
                    Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                ) {
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column {
                            DistanceRow(
                                gate = state.gate,
                                locating = state.locating,
                                onRefresh = viewModel::refreshLocation,
                            )

                            NfcRow(nfc = state.nfc)

                            if (state.needsReason) {
                                HorizontalDivider()
                                ReasonRow(
                                    kind = (state.gate as CheckInGate.NeedsReason).kind,
                                    reasons = state.reasons,
                                    selected = state.selectedReason,
                                    onSelect = viewModel::selectReason,
                                )
                            }

                            HorizontalDivider()
                            CheckInPhotoArea(
                                shopPath = state.shopPhotoPath,
                                selfiePath = state.selfiePhotoPath,
                                required = state.photoRequired,
                                busy = state.capturing,
                                onCapture = capturePhoto,
                            )

                            HorizontalDivider()
                            NoteBlock(
                                value = state.note,
                                onValueChange = viewModel::onNoteChange,
                            )
                        }
                    }

                    if (state.error != null) {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            state.error?.asString().orEmpty(),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }

                Surface(shadowElevation = 8.dp) {
                    // "Bắt đầu viếng thăm", the legacy's start_call_label, rather
                    // than "Check-in". It names what happens next instead of the
                    // record being written, which is the half the rep cares about.
                    PrimaryButton(
                        text = stringResource(R.string.checkin_start_visit),
                        onClick = viewModel::submit,
                        enabled = state.canSubmit,
                        loading = state.submitting,
                        height = 44.dp,
                        // The bar is pinned to the bottom of the window, so on a
                        // phone with three-button navigation the button lands
                        // under it and only its top edge is left tappable.
                        modifier = Modifier
                            .navigationBarsPadding()
                            .padding(16.dp),
                    )
                }
            }
        }
    }
}

/**
 * The band across the top: close, the shop's name, and a way to see it on a map.
 *
 * A close cross rather than a back arrow, as the legacy has it. Nothing here is
 * committed until the button at the bottom, so leaving is a dismissal rather
 * than a step back through anything.
 */
@Composable
private fun CheckInHeader(customer: Customer?, onClose: () -> Unit) {
    val context = LocalContext.current

    Surface(
        color = MaterialTheme.brand.header,
        shape = RoundedCornerShape(bottomStart = 20.dp, bottomEnd = 20.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .statusBarsPadding()
                .padding(start = 4.dp, end = 12.dp, top = 4.dp, bottom = 14.dp),
        ) {
            IconButton(onClick = onClose) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = stringResource(R.string.action_close),
                    tint = MaterialTheme.brand.onHeader,
                )
            }

            Column(Modifier.weight(1f)) {
                Text(
                    text = customer?.name ?: stringResource(R.string.checkin_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.brand.onHeader,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val subtitle = listOfNotNull(customer?.code, customer?.address)
                    .joinToString(" · ")
                if (subtitle.isNotBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.brand.onHeader.copy(alpha = 0.85f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            // Hidden rather than disabled on an outlet nobody has geocoded: a
            // map button that opens nothing is worse than no button.
            val lat = customer?.lat
            val lng = customer?.lng
            if (lat != null && lng != null) {
                Spacer(Modifier.width(8.dp))
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = Color.Transparent,
                    border = BorderStroke(1.dp, MaterialTheme.brand.onHeader.copy(alpha = 0.6f)),
                    modifier = Modifier.clickable {
                        val label = Uri.encode(customer.name)
                        context.startActivity(
                            Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse("geo:$lat,$lng?q=$lat,$lng($label)"),
                            ),
                        )
                    },
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    ) {
                        Icon(
                            Icons.Default.Map,
                            contentDescription = null,
                            tint = MaterialTheme.brand.onHeader,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.checkin_map),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.brand.onHeader,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Khoảng cách, with the marker that both reports and refreshes.
 *
 * The marker is only there when it has something to say — amber for a fix too
 * rough to judge by, red for out of range — which is also exactly when a rep
 * would want to try the location again. Inside the radius there is nothing to
 * fix, so no button appears. That pairing is the legacy's, and it is better than
 * a permanent refresh control: the colour tells the rep whether pressing it is
 * likely to change anything.
 */
@Composable
private fun DistanceRow(gate: CheckInGate?, locating: Boolean, onRefresh: () -> Unit) {
    val scheme = MaterialTheme.colorScheme

    val distance = when (gate) {
        is CheckInGate.Allowed -> gate.distanceM
        is CheckInGate.NeedsReason -> gate.distanceM
        is CheckInGate.Blocked -> gate.distanceM
        else -> null
    }

    val markerColor = when {
        gate is CheckInGate.NeedsReason && gate.kind == ReasonKind.GPS_LOW_ACCURACY -> LowAccuracyAmber
        gate is CheckInGate.Allowed -> null
        gate == null -> null
        else -> scheme.error
    }

    val value = when {
        locating -> stringResource(R.string.checkin_locating)
        distance != null -> stringResource(R.string.checkin_distance_m, groupDigits(distance.roundToInt()))
        else -> stringResource(R.string.checkin_no_fix)
    }

    InfoRow(
        label = stringResource(R.string.checkin_distance),
        value = value,
        valueColor = if (distance == null && !locating) scheme.error else scheme.onSurface,
    ) {
        when {
            locating -> CircularProgressIndicator(
                strokeWidth = 2.dp,
                modifier = Modifier.size(24.dp),
            )

            markerColor != null -> Surface(
                shape = CircleShape,
                color = markerColor,
                modifier = Modifier
                    .size(28.dp)
                    .clickable(onClick = onRefresh),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.LocationOn,
                        contentDescription = stringResource(R.string.checkin_relocate),
                        tint = MaterialTheme.status.onStatus,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }

            else -> Unit
        }
    }
}

/**
 * Thẻ NFC: whether the shop's tag has been tapped, and what it said.
 *
 * Hidden on a phone with no NFC, where it could only ever say no. With NFC
 * switched off it says so and opens the setting, since the tag is worth the
 * detour for a rep the GPS has put across the street.
 */
@Composable
private fun NfcRow(nfc: NfcCheck?) {
    val context = LocalContext.current
    val device = remember(nfc) { nfcState(context) }
    if (device == NfcState.UNSUPPORTED) return

    val status = MaterialTheme.status
    val (value, color) = when {
        nfc is NfcCheck.Verified -> stringResource(R.string.checkin_nfc_verified) to status.success
        nfc is NfcCheck.Checking -> stringResource(R.string.checkin_nfc_checking) to MaterialTheme.colorScheme.onSurfaceVariant
        nfc is NfcCheck.Foreign -> stringResource(R.string.checkin_nfc_foreign, nfc.customerName) to MaterialTheme.colorScheme.error
        nfc is NfcCheck.Unknown -> stringResource(R.string.checkin_nfc_unknown) to MaterialTheme.colorScheme.error
        nfc is NfcCheck.Unchecked -> stringResource(R.string.checkin_nfc_unchecked) to status.warning
        device == NfcState.OFF -> stringResource(R.string.checkin_nfc_off) to MaterialTheme.colorScheme.onSurfaceVariant
        else -> stringResource(R.string.checkin_nfc_tap) to MaterialTheme.colorScheme.onSurfaceVariant
    }

    HorizontalDivider()
    InfoRow(
        label = stringResource(R.string.checkin_nfc),
        value = value,
        valueColor = color,
        onClick = if (device == NfcState.OFF) {
            { context.startActivity(Intent(Settings.ACTION_NFC_SETTINGS)) }
        } else {
            null
        },
    ) {
        if (nfc is NfcCheck.Checking) {
            CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
        } else {
            Icon(Icons.Default.Nfc, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
        }
    }
}

/**
 * Lý do: a row on the card that opens the choices in a sheet.
 *
 * A sheet rather than the dropdown menu this replaced. A dropdown anchors itself
 * to the row and grows downward from it, so it lands over the note field and,
 * with a production list of reason codes, runs off the bottom of a card sitting
 * near the top of the screen. A sheet comes up from the edge the thumb is
 * already at, sizes itself to the list, and puts each choice on a full-width row
 * — which is the target a rep is hitting one-handed in a doorway.
 */
@Composable
private fun ReasonRow(
    kind: ReasonKind,
    reasons: List<ReasonCode>,
    selected: ReasonCode?,
    onSelect: (ReasonCode) -> Unit,
) {
    var open by remember { mutableStateOf(false) }

    Column {
        InfoRow(
            label = kind.label(),
            value = selected?.name ?: stringResource(R.string.checkin_pick_reason),
            valueColor = if (selected == null) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            onClick = { if (reasons.isNotEmpty()) open = true },
        ) {
            Icon(
                Icons.Default.ArrowDropDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (reasons.isEmpty()) {
            Text(
                text = stringResource(R.string.checkin_reasons_failed),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
            )
        }
    }

    if (open) {
        ReasonSheet(
            kind = kind,
            reasons = reasons,
            selected = selected,
            onSelect = {
                onSelect(it)
                open = false
            },
            onDismiss = { open = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReasonSheet(
    kind: ReasonKind,
    reasons: List<ReasonCode>,
    selected: ReasonCode?,
    onSelect: (ReasonCode) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        // Never a half sheet. The list is short and the rep is picking one thing;
        // a peek state would only add a drag before the choice.
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(Modifier.navigationBarsPadding()) {
            Text(
                text = kind.label(),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 12.dp),
            )
            HorizontalDivider()

            reasons.forEach { reason ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        // The whole row, not the button: a radio dot is a 20dp
                        // target and this gets tapped one-handed, outdoors.
                        .clickable { onSelect(reason) }
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                ) {
                    RadioButton(
                        selected = reason.id == selected?.id,
                        onClick = { onSelect(reason) },
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = reason.name,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}

/**
 * Hình check-in, drawn the legacy's way: before the shots, a tile with the
 * capture mark the rep taps to open the camera; after, the shop front across
 * the card with the rep's selfie pinned to its top-left corner and "Chụp lại"
 * at the top right. What is sent is those two merged into one picture.
 */
@Composable
private fun CheckInPhotoArea(
    shopPath: String?,
    selfiePath: String?,
    required: Boolean,
    busy: Boolean,
    onCapture: () -> Unit,
) {
    val primary = MaterialTheme.colorScheme.primary
    Column(Modifier.padding(16.dp)) {
        Text(
            text = stringResource(if (required) R.string.checkin_photo_required else R.string.checkin_photo),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))

        when {
            busy -> Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(4f / 3f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.checkin_merging), style = MaterialTheme.typography.labelMedium)
                }
            }

            shopPath == null -> Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .border(1.dp, primary.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                    .background(primary.copy(alpha = 0.05f))
                    .clickable(onClick = onCapture)
                    .padding(vertical = 28.dp, horizontal = 16.dp),
            ) {
                Icon(
                    Icons.Default.AddAPhoto,
                    contentDescription = null,
                    tint = primary,
                    modifier = Modifier.size(44.dp),
                )
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.checkin_take_photo), style = MaterialTheme.typography.bodyLarge, color = primary)
                Text(
                    stringResource(R.string.checkin_photo_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }

            else -> Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))) {
                AsyncImage(
                    model = File(shopPath),
                    contentDescription = stringResource(R.string.photo_outlet),
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().aspectRatio(3f / 4f),
                )
                selfiePath?.let {
                    AsyncImage(
                        model = File(it),
                        contentDescription = stringResource(R.string.photo_staff),
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .padding(10.dp)
                            .fillMaxWidth(0.25f)
                            .aspectRatio(3f / 4f)
                            .clip(RoundedCornerShape(10.dp))
                            .border(2.dp, SelfieBorder, RoundedCornerShape(10.dp)),
                    )
                }
                Surface(
                    onClick = onCapture,
                    shape = RoundedCornerShape(20.dp),
                    color = primary,
                    modifier = Modifier.align(Alignment.TopEnd).padding(10.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        Icon(
                            Icons.Default.PhotoCamera,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            stringResource(R.string.photo_retake),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                }
            }
        }
    }
}

/** Ghi chú: label above, bordered box below, as the legacy draws it. */
@Composable
private fun NoteBlock(value: String, onValueChange: (String) -> Unit) {
    Column(Modifier.padding(16.dp)) {
        Text(
            text = stringResource(R.string.note_label),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = { Text(stringResource(R.string.note_hint)) },
            shape = RoundedCornerShape(12.dp),
            minLines = 2,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * Label left, answer ranged right — the shape every row on this card shares.
 *
 * Ranging the answers right is what makes the card scannable: they line up in
 * one column, so a rep checks three facts with one glance down the edge instead
 * of reading three sentences.
 */
@Composable
private fun InfoRow(
    label: String,
    value: String,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
    onClick: (() -> Unit)? = null,
    trailing: @Composable () -> Unit = {},
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = valueColor,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(10.dp))
        trailing()
    }
}


/**
 * 21710 -> "21.710". Dots, the Vietnamese convention, and built by hand for the
 * same reason `formatDong` is: an English-locale phone would otherwise render a
 * distance the rep reads aloud with commas.
 */
private fun groupDigits(value: Int): String =
    value.toString().reversed().chunked(3).joinToString(".").reversed()

/** The legacy's light blue behind the selfie. */
private val SelfieBorder = Color(0xFF4FC3F7)

/** The legacy's amber for a fix too rough to judge a distance by. */
private val LowAccuracyAmber: Color
    @Composable get() = MaterialTheme.status.warning

@Preview(showBackground = true, heightDp = 700)
@Composable
private fun CheckInPreview() {
    MyeSalesTheme {
        Column {
            CheckInHeader(
                customer = Customer(
                    id = "c1",
                    code = "KH001",
                    name = "Tạp hóa Minh Anh",
                    address = "45 Nguyễn Trãi, Phú Hòa",
                    phone = null,
                    lat = 10.9812,
                    lng = 106.6524,
                    avatarUrl = null,
                    checkInRadiusM = null,
                ),
                onClose = {},
            )
            Column(Modifier.padding(16.dp)) {
                Card(
                    shape = RoundedCornerShape(12.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                ) {
                    Column {
                        DistanceRow(
                            gate = CheckInGate.NeedsReason(
                                ReasonKind.GPS_OUT_OF_RANGE,
                                distanceM = 21_710.0,
                            ),
                            locating = false,
                            onRefresh = {},
                        )
                        HorizontalDivider()
                        ReasonRow(
                            kind = ReasonKind.GPS_OUT_OF_RANGE,
                            reasons = emptyList(),
                            selected = null,
                            onSelect = {},
                        )
                        HorizontalDivider()
                        NoteBlock(value = "", onValueChange = {})
                    }
                }
            }
        }
    }
}

