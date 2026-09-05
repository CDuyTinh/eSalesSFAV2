package com.tinhcd.myesalessfa.feature.incall.steps

import androidx.activity.compose.BackHandler
import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.FilledIconButton
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.tinhcd.myesalessfa.core.ui.LoadingBox
import com.tinhcd.myesalessfa.core.ui.PrimaryButton
import com.tinhcd.myesalessfa.domain.model.AuditPhoto
import java.io.File

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
    val cameraPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    LaunchedEffect(Unit) { cameraPermission.launch(Manifest.permission.CAMERA) }

    LaunchedEffect(state.finished) {
        if (state.finished) onDone()
    }

    var confirmLeave by remember { mutableStateOf(false) }

    // Photos live on the device until the step is submitted, so leaving throws
    // them away — and they are the one thing here that cannot be typed again from
    // memory, because the shelf will have been restocked by tomorrow. The legacy
    // screen asks before letting that happen (msg_display_remark_back_screen).
    val unsaved = state.audit.photos.isNotEmpty() && !state.finished
    val leave = { if (unsaved) confirmLeave = true else onDone() }

    BackHandler(enabled = unsaved) { confirmLeave = true }

    Scaffold(
        topBar = {
            StepHeader(
                title = state.title.ifBlank { "Chấm trưng bày" },
                onBack = leave,
            )
        },
    ) { padding ->
        if (state.loading) {
            LoadingBox(Modifier.padding(padding))
        } else {
            AuditForm(
                state = state,
                onCapture = { takePicture.launch(viewModel.newPhotoTarget().uri) },
                onRemove = viewModel::onRemovePhoto,
                onNoteChange = viewModel::onNoteChange,
                onSubmit = viewModel::submit,
                onBack = leave,
                modifier = Modifier.padding(padding),
            )
        }
    }

    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text("Chưa gửi ảnh trưng bày") },
            text = {
                Text(
                    "Đã chụp ${state.audit.photoCount} ảnh nhưng chưa gửi. " +
                        "Thoát bây giờ sẽ mất hết.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmLeave = false
                        onDone()
                    },
                ) { Text("Thoát") }
            },
            dismissButton = {
                TextButton(onClick = { confirmLeave = false }) { Text("Ở lại") }
            },
        )
    }
}

@Composable
private fun AuditForm(
    state: DisplayAuditUiState,
    onCapture: () -> Unit,
    onRemove: (String) -> Unit,
    onNoteChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val audit = state.audit

    Column(
        modifier = modifier
            .fillMaxSize()
            .imePadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            buildString {
                append("Chụp ảnh trưng bày")
                if (audit.photoMin > 0) append(" - cần ít nhất ${audit.photoMin} ảnh")
                append(" (tối đa ${audit.photoMax})")
            },
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // Two across, as the legacy grid is. A display photo is taken to be looked
        // at — the row of 110dp tiles this replaces was too small to tell a full
        // shelf from an empty one, which is the only question it has to answer.
        if (audit.photos.isNotEmpty()) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.heightIn(max = 420.dp),
            ) {
                items(audit.photos, key = { it.localPath }) { photo ->
                    PhotoThumbnail(photo = photo, onRemove = { onRemove(photo.localPath) })
                }
            }
        }

        if (audit.canAddPhoto) {
            OutlinedButton(
                onClick = onCapture,
                enabled = !state.capturing && !state.submitting,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.PhotoCamera, contentDescription = null)
                Text(
                    if (audit.photos.isEmpty()) "  Chụp ảnh" else "  Chụp thêm ảnh",
                )
            }
        } else {
            // Gone rather than greyed. A disabled button reads as something the
            // rep failed to earn; there is simply nothing more to take.
            Text(
                "Đã đủ ${audit.photoMax} ảnh. Bỏ bớt một ảnh nếu muốn chụp lại.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        OutlinedTextField(
            value = audit.note,
            onValueChange = onNoteChange,
            label = { Text("Ghi chú (tùy chọn)") },
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

        Surface(shadowElevation = 0.dp) {
            Column {
                if (audit.photosStillNeeded > 0) {
                    Text(
                        "Còn thiếu ${audit.photosStillNeeded} ảnh",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else if (audit.photos.isNotEmpty()) {
                    // The size is the honest answer to "why is this slow" on a
                    // connection measured in tens of kilobytes per second.
                    Text(
                        "${audit.photoCount} ảnh - ${audit.totalSizeBytes / 1024} KB sẽ được gửi",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        PrimaryButton(
            text = "Hoàn thành bước này",
            onClick = onSubmit,
            enabled = audit.canSubmit,
            loading = state.submitting || state.capturing,
        )

        OutlinedButton(
            onClick = onBack,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Quay lại") }
    }
}

@Composable
private fun PhotoThumbnail(photo: AuditPhoto, onRemove: () -> Unit) {
    Card(shape = RoundedCornerShape(12.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f),
        ) {
            // Loaded from the local file: nothing has been uploaded yet, and the rep
            // needs to see the shot they just took to judge whether to keep it.
            AsyncImage(
                model = File(photo.localPath),
                contentDescription = "Ảnh trưng bày",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )

            FilledIconButton(
                onClick = onRemove,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(2.dp)
                    .size(28.dp),
            ) { Icon(Icons.Default.Close, contentDescription = "Bỏ ảnh này") }

            Row(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(4.dp),
            ) {
                Text(
                    "${photo.sizeBytes / 1024} KB",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }
    }
}
