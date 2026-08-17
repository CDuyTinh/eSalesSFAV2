package com.tinhcd.myesalessfa.feature.incall.steps

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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

@OptIn(ExperimentalMaterial3Api::class)
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

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(state.title.ifBlank { "Chấm trưng bày" }) })
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
                onBack = onDone,
                modifier = Modifier.padding(padding),
            )
        }
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
            if (audit.photoMin > 0) {
                "Chụp ảnh trưng bày - cần ít nhất ${audit.photoMin} ảnh"
            } else {
                "Chụp ảnh trưng bày"
            },
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (audit.photos.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(audit.photos, key = { it.localPath }) { photo ->
                    PhotoThumbnail(photo = photo, onRemove = { onRemove(photo.localPath) })
                }
            }
        }

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
    Card(shape = RoundedCornerShape(8.dp)) {
        Box(Modifier.size(110.dp)) {
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
