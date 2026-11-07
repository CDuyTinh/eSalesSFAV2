package com.tinhcd.myesalessfa.feature.scan

import android.media.AudioManager
import android.media.ToneGenerator
import androidx.annotation.OptIn
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.tinhcd.myesalessfa.R
import com.tinhcd.myesalessfa.core.ui.UiText
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executors
import kotlin.coroutines.resume

/** The same code seen again within this long is the same box still in frame. */
private const val REPEAT_MS = 1_500L

/**
 * A full-screen scanner that keeps reading until it is closed.
 *
 * Built for walking a shelf: every code read is handed to [onCode] at once,
 * with a beep and a buzz, and whatever [onCode] answers stays on screen until
 * the next one — the rep's eyes are on the boxes, not the phone. A box held
 * still is counted once rather than once per frame; moving to another code, or
 * the same one after a pause, counts again.
 *
 * Retail codes only (EAN, UPC, Code 128), which is what goods carry; a QR code
 * on a poster in the background is not mistaken for a product. The model is
 * bundled, so it reads with no signal.
 */
@Composable
fun BarcodeScanner(
    onCode: (String) -> UiText,
    onClose: () -> Unit,
) {
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        ScannerContent(onCode = onCode, onClose = onClose)
    }
}

@OptIn(ExperimentalGetImage::class)
@Composable
private fun ScannerContent(
    onCode: (String) -> UiText,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val haptics = LocalHapticFeedback.current
    val currentOnCode by rememberUpdatedState(onCode)

    val previewView = remember {
        PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
    }
    val scanner = remember {
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(
                    Barcode.FORMAT_EAN_13,
                    Barcode.FORMAT_EAN_8,
                    Barcode.FORMAT_UPC_A,
                    Barcode.FORMAT_UPC_E,
                    Barcode.FORMAT_CODE_128,
                )
                .build(),
        )
    }
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    // A beep the rep hears over a busy shop; null where the device has no tone
    // generator to give, in which case the buzz alone has to do.
    val beeper = remember { runCatching { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80) }.getOrNull() }

    var provider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var torch by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<UiText?>(null) }

    // Touched only on the main thread, where the reads are delivered.
    var lastCode by remember { mutableStateOf<String?>(null) }
    var lastAt by remember { mutableStateOf(0L) }

    fun onRead(code: String) {
        val now = System.currentTimeMillis()
        if (code == lastCode && now - lastAt < REPEAT_MS) {
            lastAt = now
            return
        }
        lastCode = code
        lastAt = now
        beeper?.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        result = currentOnCode(code)
    }

    LaunchedEffect(Unit) {
        val cameras = suspendCancellableCoroutine { cont ->
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener({ cont.resume(future.get()) }, ContextCompat.getMainExecutor(context))
        }.also { provider = it }
        if (!cameras.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) {
            onClose()
            return@LaunchedEffect
        }
        val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
        // Only the newest frame matters: a frame queued behind a slow read is
        // already out of date by the time the reader gets to it.
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
        val main = ContextCompat.getMainExecutor(context)
        analysis.setAnalyzer(analysisExecutor) { proxy ->
            val image = proxy.image
            if (image == null) {
                proxy.close()
                return@setAnalyzer
            }
            scanner.process(InputImage.fromMediaImage(image, proxy.imageInfo.rotationDegrees))
                .addOnSuccessListener(main) { codes ->
                    codes.firstNotNullOfOrNull { it.rawValue }?.let(::onRead)
                }
                .addOnCompleteListener { proxy.close() }
        }
        cameras.unbindAll()
        camera = cameras.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
    }
    DisposableEffect(Unit) {
        onDispose {
            provider?.unbindAll()
            scanner.close()
            analysisExecutor.shutdown()
            beeper?.release()
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())

        // Where to hold the code. The reader looks at the whole frame; this is
        // for the rep's aim, and it is the shape of a barcode, not a QR code.
        Box(
            Modifier
                .align(Alignment.Center)
                .fillMaxWidth(0.8f)
                .aspectRatio(2f)
                .border(3.dp, Color.White, RoundedCornerShape(12.dp)),
        )

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
                    stringResource(R.string.scan_title),
                    color = Color.White,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    stringResource(R.string.scan_hint),
                    color = Color.White.copy(alpha = 0.8f),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            if (camera?.cameraInfo?.hasFlashUnit() == true) {
                IconButton(onClick = {
                    torch = !torch
                    camera?.cameraControl?.enableTorch(torch)
                }) {
                    Icon(
                        if (torch) Icons.Default.FlashOff else Icons.Default.FlashOn,
                        contentDescription = stringResource(if (torch) R.string.scan_torch_off else R.string.scan_torch_on),
                        tint = Color.White,
                    )
                }
            }
        }

        Text(
            text = result?.asString() ?: stringResource(R.string.scan_waiting),
            color = Color.White,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.6f))
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 24.dp)
                // Read out by TalkBack as each scan lands.
                .semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}
