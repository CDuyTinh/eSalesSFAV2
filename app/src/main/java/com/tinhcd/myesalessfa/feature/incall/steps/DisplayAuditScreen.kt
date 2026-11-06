package com.tinhcd.myesalessfa.feature.incall.steps

import android.Manifest
import com.tinhcd.myesalessfa.R
import androidx.compose.ui.res.stringResource
import com.tinhcd.myesalessfa.core.R as CoreR
import com.tinhcd.myesalessfa.core.permission.rememberPermissionRequest
import com.tinhcd.myesalessfa.core.ui.theme.status
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.tinhcd.myesalessfa.core.ui.ButtonPair
import com.tinhcd.myesalessfa.core.ui.PrimaryButton
import com.tinhcd.myesalessfa.core.ui.SkeletonList
import com.tinhcd.myesalessfa.core.ui.formatDong
import com.tinhcd.myesalessfa.domain.model.AuditPhoto
import com.tinhcd.myesalessfa.domain.model.DisplayProgram
import java.io.File

/**
 * Chấm trưng bày.
 *
 * The step is not "photograph a shelf"; it is "check a commitment". Head office
 * runs display programmes, the outlet signed up at a level, the level names how
 * many facings it is worth, and the rep's job is to count what is actually there
 * and say whether the outlet earned it. So the screen is two pages: pick the
 * programme, then score it — the shape of the legacy's own display list and
 * remark dialog.
 *
 * An outlet in no programme goes straight to the second page and records the
 * shelf, which is all this step ever did before.
 */
@Composable
fun DisplayAuditScreen(
    onDone: () -> Unit,
    viewModel: DisplayAuditViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    val takePicture = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { saved -> viewModel.onPhotoTaken(saved) }

    // The manifest declares CAMERA, which makes ACTION_IMAGE_CAPTURE require the
    // grant on top of the FileProvider uri. Asked for on entry, so the rep is not
    // interrupted by a dialog after framing the shot.
    // Asked on entry, as before, and again from the capture button: refused,
    // the button explains why rather than opening a camera that cannot start.
    val cameraPermission = rememberPermissionRequest(
        listOf(Manifest.permission.CAMERA),
        stringResource(CoreR.string.permission_rationale_camera),
    )

    LaunchedEffect(Unit) { cameraPermission.launch() }

    LaunchedEffect(state.finished) {
        if (state.finished) onDone()
    }

    var confirmLeave by remember { mutableStateOf(false) }

    // Photos live on the device until the programme is submitted, so leaving
    // throws them away — and they are the one thing here that cannot be typed
    // again from memory, because the shelf will have been restocked by tomorrow.
    // The legacy screen asks before letting that happen
    // (msg_display_remark_back_screen).
    val unsaved = state.audit.photos.isNotEmpty() && !state.finished

    val onScoring = state.page == DisplayAuditPage.AUDIT

    // Backing out of one programme returns to the list; backing out of the list
    // leaves the step. With no programmes at all there is no list to return to.
    val back = {
        when {
            unsaved -> confirmLeave = true
            onScoring && state.programs.isNotEmpty() -> viewModel.onLeaveProgram()
            else -> onDone()
        }
    }

    BackHandler(enabled = unsaved || (onScoring && state.programs.isNotEmpty())) { back() }

    Scaffold(
        topBar = {
            StepHeader(
                title = when {
                    onScoring && state.audit.program != null -> state.audit.program!!.programName
                    else -> state.title.ifBlank { stringResource(R.string.sfb_62) }
                },
                onBack = back,
                subtitle = if (state.programs.isEmpty()) {
                    null
                } else {
                    stringResource(R.string.sft_10, state.scoredCount, state.programs.size)
                },
            )
        },
    ) { padding ->
        when {
            state.loading -> SkeletonList(Modifier.padding(padding), count = 3, lines = 3)

            state.page == DisplayAuditPage.PROGRAMS -> ProgramList(
                programs = state.programs,
                onOpen = viewModel::onOpenProgram,
                onBack = onDone,
                modifier = Modifier.padding(padding),
            )

            else -> AuditForm(
                state = state,
                onCapture = { cameraPermission.launch { takePicture.launch(viewModel.newPhotoTarget().uri) } },
                onRemove = viewModel::onRemovePhoto,
                onNoteChange = viewModel::onNoteChange,
                onCountedFacesChange = viewModel::onCountedFacesChange,
                onAchievedChange = viewModel::onAchievedChange,
                onSubmit = viewModel::submit,
                onBack = back,
                modifier = Modifier.padding(padding),
            )
        }
    }

    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text(stringResource(R.string.sfa_13)) },
            text = {
                Text(
                    stringResource(R.string.sft_11, state.audit.photoCount) +
                        stringResource(R.string.sfb_63),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmLeave = false
                        if (state.programs.isNotEmpty()) viewModel.onLeaveProgram() else onDone()
                    },
                ) { Text(stringResource(R.string.sfa_14)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmLeave = false }) { Text(stringResource(R.string.sfa_15)) }
            },
        )
    }
}

// -----------------------------------------------------------------------------
// Page one: which commitment
// -----------------------------------------------------------------------------

@Composable
private fun ProgramList(
    programs: List<DisplayProgram>,
    onOpen: (DisplayProgram) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        LazyColumn(
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.weight(1f),
        ) {
            items(programs, key = { it.programId }) { program ->
                ProgramCard(program = program, onClick = { onOpen(program) })
            }
        }

        Surface(shadowElevation = 8.dp) {
            Column(Modifier.padding(16.dp)) {
                OutlinedButton(
                    onClick = onBack,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp),
                ) { Text(stringResource(R.string.action_back)) }
            }
        }
    }
}

@Composable
private fun ProgramCard(program: DisplayProgram, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
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
                        "${program.programCode} | ${program.levelName}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (program.isScored) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = if (program.achieved == true) Pass else Fail,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                }

                Icon(
                    Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.outline,
                )
            }

            Spacer(Modifier.height(8.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(8.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.sft_12, program.requiredFaces),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )

                when {
                    // The verdict, not the count: a display can miss the target
                    // and still be built right, which is the whole reason the rep
                    // is asked rather than the arithmetic.
                    program.achieved == true -> StatusPill(stringResource(R.string.sfa_16), Pass)
                    program.achieved == false -> StatusPill(stringResource(R.string.sfa_17), Fail)
                    // Signed up and waiting on head office. Still audited: refusing
                    // to score it is how a rep gets blamed for the paperwork.
                    program.isPending -> StatusPill(
                        stringResource(R.string.sfa_20),
                        MaterialTheme.colorScheme.tertiary,
                    )

                    else -> StatusPill(stringResource(R.string.sfa_18), MaterialTheme.colorScheme.outline)
                }
            }

            if (program.isScored) {
                Text(
                    "Thực tế ${program.countedFaces} mặt - ${program.photoCount} ảnh",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (program.bonusAmount > 0) {
                Text(
                    stringResource(R.string.sft_14, formatDong(program.bonusAmount)),
                    style = MaterialTheme.typography.labelSmall,
                    color = Pass,
                )
            }
        }
    }
}

@Composable
private fun StatusPill(text: String, tint: Color) {
    Surface(
        color = tint.copy(alpha = 0.12f),
        shape = RoundedCornerShape(6.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            color = tint,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

// -----------------------------------------------------------------------------
// Page two: how this one looks
// -----------------------------------------------------------------------------

@Composable
private fun AuditForm(
    state: DisplayAuditUiState,
    onCapture: () -> Unit,
    onRemove: (String) -> Unit,
    onNoteChange: (String) -> Unit,
    onCountedFacesChange: (Int) -> Unit,
    onAchievedChange: (Boolean) -> Unit,
    onSubmit: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val audit = state.audit

    Column(modifier.fillMaxSize().imePadding()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            audit.program?.let { program ->
                ScoreCard(
                    program = program,
                    countedFaces = audit.countedFaces,
                    achieved = audit.achieved,
                    onCountedFacesChange = onCountedFacesChange,
                    onAchievedChange = onAchievedChange,
                )

                // Above the camera on purpose. The rep reads the standard, looks at
                // the shelf, then photographs it — asking for the verdict before
                // showing what the verdict is against is how the old screen made
                // "đạt" a guess.
                StandardCard(program)
            }

            Text(
                buildString {
                    append(stringResource(R.string.sfa_22))
                    if (audit.photoMin > 0) append(stringResource(R.string.sft_15, audit.photoMin))
                    append(stringResource(R.string.sft_16, audit.photoMax))
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // Two across, as the legacy grid is. A display photo is taken to be
            // looked at — the row of 110dp tiles this replaces was too small to
            // tell a full shelf from an empty one, which is the only question it
            // has to answer. Laid out by hand rather than with a lazy grid: this
            // column scrolls, and a lazy grid inside a scrolling parent has no
            // height to measure against.
            audit.photos.chunked(2).forEach { pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    pair.forEach { photo ->
                        Box(Modifier.weight(1f)) {
                            PhotoThumbnail(
                                photo = photo,
                                onRemove = { onRemove(photo.localPath) },
                            )
                        }
                    }
                    // Keeps a lone last photo half-width instead of stretching it
                    // to twice the size of its neighbours above.
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }

            if (audit.canAddPhoto) {
                OutlinedButton(
                    onClick = onCapture,
                    enabled = !state.capturing && !state.submitting,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp),
                ) {
                    Icon(Icons.Default.PhotoCamera, contentDescription = null)
                    Text(if (audit.photos.isEmpty()) stringResource(R.string.sfb_64) else stringResource(R.string.sfb_65))
                }
            } else {
                // Gone rather than greyed. A disabled button reads as something the
                // rep failed to earn; there is simply nothing more to take.
                Text(
                    stringResource(R.string.sft_17, audit.photoMax),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            OutlinedTextField(
                value = audit.note,
                onValueChange = onNoteChange,
                label = { Text(stringResource(R.string.sfa_19)) },
                minLines = 3,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(110.dp),
            )

            if (state.error != null) {
                Text(
                    state.error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }

        Surface(shadowElevation = 8.dp) {
            Column(Modifier.padding(16.dp)) {
                when {
                    audit.photosStillNeeded > 0 -> Text(
                        stringResource(R.string.sft_18, audit.photosStillNeeded),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )

                    audit.program != null && audit.achieved == null -> Text(
                        stringResource(R.string.sfb_66),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )

                    audit.photos.isNotEmpty() -> Text(
                        // The size is the honest answer to "why is this slow" on a
                        // connection measured in tens of kilobytes per second.
                        stringResource(R.string.sft_19, audit.photoCount, audit.totalSizeBytes / 1024),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Spacer(Modifier.height(8.dp))

                ButtonPair(
                    text = if (audit.program == null) stringResource(R.string.sfa_25) else stringResource(R.string.sfb_67),
                    onClick = onSubmit,
                    onBack = onBack,
                    enabled = audit.canSubmit,
                    loading = state.submitting || state.capturing,
                    height = 44.dp,
                )
            }
        }
    }
}

/**
 * The two numbers side by side, which is the whole point of the step.
 *
 * The target is printed rather than left to memory, and the shortfall under it,
 * because a rep counting eleven facings against a twelve-facing commitment is
 * the exact case this screen exists to catch.
 */
@Composable
private fun ScoreCard(
    program: DisplayProgram,
    countedFaces: Int?,
    achieved: Boolean?,
    onCountedFacesChange: (Int) -> Unit,
    onAchievedChange: (Boolean) -> Unit,
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${program.programCode} | ${program.levelName}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (program.isPending) {
                    StatusPill(stringResource(R.string.sfa_20), MaterialTheme.colorScheme.tertiary)
                } else if (!program.registered) {
                    // No signup behind it: the programme applies to the whole route.
                    StatusPill(stringResource(R.string.sfa_21), MaterialTheme.colorScheme.outline)
                }
            }

            if (!program.specification.isNullOrBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    program.specification!!,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(10.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(10.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.sfb_68),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "${program.requiredFaces}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            Spacer(Modifier.height(8.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.sfb_69),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                QtyStepper(
                    qty = countedFaces,
                    onQtyChange = onCountedFacesChange,
                    placeholder = "0",
                )
            }

            if (countedFaces != null) {
                val short = program.shortfall(countedFaces)
                Spacer(Modifier.height(6.dp))
                Text(
                    if (short > 0) stringResource(R.string.sft_20, short) else stringResource(R.string.sfc_19),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (short > 0) Fail else Pass,
                )
            }

            Spacer(Modifier.height(12.dp))

            Text(
                stringResource(R.string.sfb_70),
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(Modifier.height(6.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                VerdictButton(
                    text = stringResource(R.string.sfa_16),
                    tint = Pass,
                    selected = achieved == true,
                    onClick = { onAchievedChange(true) },
                    modifier = Modifier.weight(1f),
                )
                VerdictButton(
                    text = stringResource(R.string.sfa_17),
                    tint = Fail,
                    selected = achieved == false,
                    onClick = { onAchievedChange(false) },
                    modifier = Modifier.weight(1f),
                )
            }

            if (program.bonusAmount > 0) {
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.sft_21, formatDong(program.bonusAmount)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Neither choice is the default. Nothing is selected until the rep says so,
 * because a pre-selected verdict is a verdict nobody gave.
 */
@Composable
private fun VerdictButton(
    text: String,
    tint: Color,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (selected) tint else MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier
            .height(42.dp)
            .clickable(onClick = onClick),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = if (selected) MaterialTheme.status.onStatus else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PhotoThumbnail(photo: AuditPhoto, onRemove: () -> Unit) {
    Card(
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f),
        ) {
            // Loaded from the local file: nothing has been uploaded yet, and the rep
            // needs to see the shot they just took to judge whether to keep it.
            AsyncImage(
                model = File(photo.localPath),
                contentDescription = stringResource(R.string.sfa_22),
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )

            FilledIconButton(
                onClick = onRemove,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(2.dp)
                    .size(28.dp),
            ) { Icon(Icons.Default.Close, contentDescription = stringResource(R.string.sfa_23)) }

            Row(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.35f))
                    .padding(horizontal = 6.dp, vertical = 3.dp),
            ) {
                Text(
                    "${photo.sizeBytes / 1024} KB",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium,
                    color = Color.White,
                )
            }
        }
    }
}

/** Pass and fail, the colours the legacy display list marks its rows in. */
private val Pass: Color
    @Composable get() = MaterialTheme.status.success
private val Fail: Color
    @Composable get() = MaterialTheme.status.danger

/**
 * What this level asks for: head office's photographs of the shelf, and the
 * products that have to be on it.
 *
 * API_GetListDisplay returns both — the six DisplayImage columns as a second
 * result set, and OM_TDisplayLevelInvt as the line items — and the rep over there
 * reads them before scoring. Without them "đạt hay không đạt" is asked against a
 * standard nobody has shown them.
 *
 * Absent entirely when the level configures neither, rather than an empty card:
 * plenty of programmes are a paragraph of specification and a facing count.
 */
@Composable
private fun StandardCard(program: DisplayProgram) {
    if (program.sampleImages.isEmpty() && program.requiredItems.isEmpty()) return

    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                stringResource(R.string.sfb_71),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
            )

            if (program.sampleImages.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                // Sideways rather than stacked: they are variations of one shelf —
                // front on, from the side, close on the labels — and the rep swipes
                // through them the way they would through a photo roll.
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(program.sampleImages, key = { it.imageUrl }) { sample ->
                        Column(Modifier.width(150.dp)) {
                            AsyncImage(
                                model = sample.imageUrl,
                                contentDescription = sample.caption,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(112.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant),
                            )
                            sample.caption?.let { caption ->
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    caption,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }

            if (program.requiredItems.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(R.string.sfb_72),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))

                program.requiredItems.forEach { item ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(vertical = 3.dp),
                    ) {
                        Text(
                            item.productName,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        // Said on the line rather than left to a legend: an optional
                        // line is one the rep may leave off the shelf, which changes
                        // what they are counting.
                        if (!item.isRequired) {
                            Text(
                                stringResource(R.string.sfb_73),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            "${item.qty} ${item.unitName.lowercase()}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }
    }
}
