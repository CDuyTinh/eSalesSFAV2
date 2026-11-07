package com.tinhcd.myesalessfa.feature.nfc

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.nfc.NfcAdapter
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/** Whether this phone can read tags at all, and whether NFC is switched on. */
enum class NfcState { UNSUPPORTED, OFF, READY }

fun nfcState(context: Context): NfcState {
    val adapter = NfcAdapter.getDefaultAdapter(context) ?: return NfcState.UNSUPPORTED
    return if (adapter.isEnabled) NfcState.READY else NfcState.OFF
}

/**
 * The tag's UID as the server stores it: upper-case hex, no separators.
 * Android hands it over as raw bytes, first byte first.
 */
fun ByteArray.toTagUid(): String = joinToString("") { "%02X".format(it) }

/**
 * The screens currently listening, newest last. The activity has one reader
 * mode, so screens cannot each switch it on and off: during a navigation the
 * screen leaving would turn it off under the one arriving. Instead each screen
 * joins this list while it is resumed, the newest gets the tag, and reader
 * mode goes off only when nobody is left. Main thread only.
 */
private val listeners = mutableListOf<(String) -> Unit>()

private const val READER_FLAGS = NfcAdapter.FLAG_READER_NFC_A or
    NfcAdapter.FLAG_READER_NFC_B or
    NfcAdapter.FLAG_READER_NFC_F or
    NfcAdapter.FLAG_READER_NFC_V or
    NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK

/**
 * Reads NFC tags while the calling screen is on top, handing each tag's UID to
 * [onTag] on the main thread.
 *
 * Reader mode rather than an intent filter: the app is open and waiting for
 * the tap, so the tag should come straight here, not to the system's "new tag"
 * sheet or another app that claims NDEF. NDEF is not read at all -- the UID is
 * the whole message -- which also skips the system's check that would
 * otherwise slow every tap.
 *
 * Joins at ON_RESUME and leaves at ON_PAUSE, which is when the platform lets
 * reader mode change; a phone without NFC, or with it off, never calls back.
 */
@Composable
fun NfcTagReader(enabled: Boolean = true, onTag: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnTag by rememberUpdatedState(onTag)
    val listener = remember { { uid: String -> currentOnTag(uid) } }

    DisposableEffect(lifecycleOwner, enabled) {
        val activity = context.findActivity()
        val adapter = NfcAdapter.getDefaultAdapter(context)
        if (!enabled || activity == null || adapter == null) return@DisposableEffect onDispose {}

        val main = Handler(Looper.getMainLooper())
        val join = {
            listeners.remove(listener)
            listeners.add(listener)
            adapter.enableReaderMode(
                activity,
                { tag -> tag.id?.toTagUid()?.let { uid -> main.post { listeners.lastOrNull()?.invoke(uid) } } },
                READER_FLAGS,
                null,
            )
        }
        val leave = {
            listeners.remove(listener)
            if (listeners.isEmpty()) runCatching { adapter.disableReaderMode(activity) }
        }

        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> join()
                Lifecycle.Event.ON_PAUSE -> leave()
                else -> Unit
            }
        }
        // A new observer is replayed the events up to the current state, so a
        // screen already resumed joins here too.
        lifecycleOwner.lifecycle.addObserver(observer)

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            leave()
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
