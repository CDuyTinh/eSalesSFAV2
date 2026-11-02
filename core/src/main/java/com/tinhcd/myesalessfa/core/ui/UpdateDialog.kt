package com.tinhcd.myesalessfa.core.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties

/**
 * Tells the rep a newer build exists.
 *
 * [required] is the minimum head office supports having moved past this build:
 * the dialog cannot be dismissed, by tapping outside or by back, because the
 * server may already refuse what this build sends. Otherwise "Để sau" closes it.
 *
 * @param url where the build is; null opens the app's store page.
 */
@Composable
fun UpdateDialog(
    required: Boolean,
    versionName: String,
    note: String?,
    url: String?,
    onLater: () -> Unit,
) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = { if (!required) onLater() },
        properties = DialogProperties(dismissOnBackPress = !required, dismissOnClickOutside = !required),
        icon = { Icon(Icons.Default.SystemUpdate, contentDescription = null) },
        title = { Text(if (required) "Cần cập nhật ứng dụng" else "Đã có phiên bản mới") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (required) {
                        "Phiên bản $versionName không còn được hỗ trợ. Vui lòng cập nhật để tiếp tục làm việc."
                    } else {
                        "Bạn đang dùng phiên bản $versionName. Cập nhật để có các thay đổi mới nhất."
                    },
                    style = MaterialTheme.typography.bodyLarge,
                )
                note?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = { TextButton(onClick = { openUpdate(context, url) }) { Text("Cập nhật") } },
        dismissButton = if (required) null else ({ TextButton(onClick = onLater) { Text("Để sau") } }),
    )
}

/** The configured link, or the store page for this package when there is none. */
private fun openUpdate(context: Context, url: String?) {
    val target = url ?: "market://details?id=${context.packageName}"
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(target)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        // No store app: the store's web page instead.
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
