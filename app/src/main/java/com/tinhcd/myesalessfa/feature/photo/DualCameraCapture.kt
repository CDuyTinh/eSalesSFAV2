package com.tinhcd.myesalessfa.feature.photo

import android.widget.Toast
import com.tinhcd.myesalessfa.R
import androidx.compose.ui.res.stringResource
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil3.compose.AsyncImage
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume

/**
 * The check-in camera, as the legacy's CheckInCameraForm runs it: the shop
 * front on the back camera, then straight on to the front camera for the rep,
 * and back to the screen with both. A camera app reached by intent cannot be
 * told which lens to open, so this one runs inside the app.
 *
 * [newFile] is asked for each shot's path. [onDone] gets the shop photo and the
 * selfie, the selfie null on a phone with no front camera. Leaving part-way
 * deletes whatever was taken and calls [onCancel].
 */
@Composable
fun DualCameraCapture(
    newFile: () -> String,
    onDone: (shopPath: String, selfiePath: String?) -> Unit,
    onCancel: () -> Unit,
) {
    var shopPath by remember { mutableStateOf<String?>(null) }
    val front = shopPath != null

    val cancel = {
        shopPath?.let { File(it).delete() }
        onCancel()
    }

    Dialog(
        onDismissRequest = cancel,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        CameraStep(
            front = front,
            newFile = newFile,
            onShot = { path ->
                if (front) onDone(shopPath!!, path) else shopPath = path
            },
            onNoFrontCamera = { onDone(shopPath!!, null) },
            onClose = cancel,
            shopPreview = shopPath,
        )
    }
}

@Composable
private fun CameraStep(
    front: Boolean,
    newFile: () -> String,
    onShot: (String) -> Unit,
    onNoFrontCamera: () -> Unit,
    onClose: () -> Unit,
    shopPreview: String?,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val shotFailed = stringResource(R.string.camera_shot_failed)
    val previewView = remember {
        PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
    }
    val imageCapture = remember(front) {
        ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
    }
    var provider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var ready by remember(front) { mutableStateOf(false) }
    var shooting by remember(front) { mutableStateOf(false) }

    LaunchedEffect(front) {
        val cameras = suspendCancellableCoroutine { cont ->
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener({ cont.resume(future.get()) }, ContextCompat.getMainExecutor(context))
        }.also { provider = it }
        val selector = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
        if (!cameras.hasCamera(selector)) {
            if (front) onNoFrontCamera() else onClose()
            return@LaunchedEffect
        }
        val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
        cameras.unbindAll()
        imageCapture.targetRotation = previewView.display?.rotation ?: imageCapture.targetRotation
        cameras.bindToLifecycle(lifecycleOwner, selector, preview, imageCapture)
        ready = true
    }
    DisposableEffect(Unit) {
        onDispose { provider?.unbindAll() }
    }

    fun shoot() {
        if (!ready || shooting) return
        shooting = true
        val path = newFile()
        imageCapture.takePicture(
            ImageCapture.OutputFileOptions.Builder(File(path)).build(),
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) = onShot(path)

                override fun onError(exception: ImageCaptureException) {
                    File(path).delete()
                    shooting = false
                    Toast.makeText(context, shotFailed, Toast.LENGTH_SHORT).show()
                }
            },
        )
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.45f))
                .statusBarsPadding()
                .padding(horizontal = 4.dp, vertical = 6.dp),
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.action_close), tint = Color.White)
            }
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(if (front) R.string.camera_step_staff else R.string.camera_step_outlet),
                    color = Color.White,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    stringResource(if (front) R.string.camera_front else R.string.camera_back),
                    color = Color.White.copy(alpha = 0.8f),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.45f))
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 20.dp),
        ) {
            Box(Modifier.width(56.dp)) {
                shopPreview?.let {
                    AsyncImage(
                        model = File(it),
                        contentDescription = stringResource(R.string.photo_outlet),
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(56.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .border(2.dp, Color.White, RoundedCornerShape(8.dp)),
                    )
                }
            }
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(72.dp)
                    .border(4.dp, Color.White, CircleShape)
                    .padding(8.dp)
                    .clip(CircleShape)
                    .background(if (ready && !shooting) Color.White else Color.White.copy(alpha = 0.4f))
                    .clickable(enabled = ready && !shooting, onClick = ::shoot),
            ) {
                if (shooting) CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
            }
            Spacer(Modifier.width(56.dp))
        }
    }
}
