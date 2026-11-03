package com.tinhcd.myesalessfa.feature.photo

import android.Manifest
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import java.io.File

/**
 * The camera, behind its permission. Returns what to call to take a picture:
 * the permission is asked first, and the camera opens only once it is granted.
 *
 * [newTarget] is asked for where to write only at that point, so a refused
 * permission leaves no empty file behind.
 */
@Composable
fun rememberCameraCapture(newTarget: () -> Uri, onTaken: (saved: Boolean) -> Unit): () -> Unit {
    val takePicture = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { saved -> onTaken(saved) }
    val permission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) takePicture.launch(newTarget()) }
    return { permission.launch(Manifest.permission.CAMERA) }
}

/**
 * One photo the rep owes: a button to take it, then the thumbnail with retake
 * and remove. Used at check-in (the shop front), check-out and both punches of
 * the working day.
 */
@Composable
fun PhotoBlock(
    title: String,
    path: String?,
    required: Boolean,
    busy: Boolean,
    onCapture: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(16.dp),
) {
    Column(modifier.padding(contentPadding), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            if (required) "$title (bắt buộc)" else title,
            style = MaterialTheme.typography.titleSmall,
        )
        if (path == null) {
            OutlinedButton(onClick = onCapture, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.PhotoCamera, contentDescription = null)
                Text(if (busy) "  Đang lưu ảnh…" else "  Chụp ảnh")
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AsyncImage(
                    model = File(path),
                    contentDescription = title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(84.dp).clip(RoundedCornerShape(10.dp)),
                )
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = onCapture, enabled = !busy) { Text("Chụp lại") }
                    TextButton(onClick = onRemove, enabled = !busy) { Text("Xoá ảnh") }
                }
            }
        }
    }
}
