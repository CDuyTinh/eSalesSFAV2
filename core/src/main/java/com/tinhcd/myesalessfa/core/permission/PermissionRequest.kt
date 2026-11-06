package com.tinhcd.myesalessfa.core.permission

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.tinhcd.myesalessfa.core.R

/** Asks for a set of permissions; [launch] runs its callback once they are held. */
@Stable
class PermissionRequest internal constructor(
    private val isGranted: () -> Boolean,
    private val ask: (onGranted: () -> Unit) -> Unit,
) {
    /** Whether the permissions are held right now. */
    val granted: Boolean get() = isGranted()

    /** Runs [onGranted] at once if the permissions are held, else after the rep grants them. */
    fun launch(onGranted: () -> Unit = {}) = if (isGranted()) onGranted() else ask(onGranted)
}

/**
 * One way to ask for a permission, for every screen.
 *
 * Granted: the action runs. Refused: a dialog says why the app needs it, in
 * [rationale], with a button to ask again. Refused for good: the same reason,
 * and a button to the app's settings page; coming back from there with the
 * permission on carries on with the action that asked for it.
 *
 * [onDenied] runs when the rep leaves without granting, for a screen that has
 * something to show instead (a check-in without a fix records a reason).
 *
 * The screens used to call the system prompt themselves and ignore the
 * answer, so a refused camera left "Chụp ảnh" doing nothing, and a permission
 * refused twice could never be asked for again from inside the app.
 */
@Composable
fun rememberPermissionRequest(
    permissions: List<String>,
    rationale: String,
    anyOf: Boolean = false,
    // For a prompt the screen asks for unprompted (notifications on opening the
    // app): once refused for good, say nothing rather than send the rep to
    // Settings every time the app opens.
    quietWhenBlocked: Boolean = false,
    onDenied: () -> Unit = {},
): PermissionRequest {
    val context = LocalContext.current
    val currentOnDenied by rememberUpdatedState(onDenied)
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    var dialog by remember { mutableStateOf<PermissionOutcome?>(null) }
    var awaitingSettings by remember { mutableStateOf(false) }

    fun held(): Boolean {
        val states = permissions.map { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
        return if (anyOf) states.any { it } else states.all { it }
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        val activity = context.findActivity()
        val outcome = permissionOutcome(
            results = permissions.associateWith { results[it] ?: held() },
            anyOf = anyOf,
            canExplain = { activity?.shouldShowRequestPermissionRationale(it) == true },
            askedBefore = { AskedBefore.get(context, it) },
        )
        permissions.forEach { AskedBefore.set(context, it) }
        when (outcome) {
            PermissionOutcome.GRANTED -> pending?.invoke().also { pending = null }
            PermissionOutcome.BLOCKED -> if (quietWhenBlocked) {
                pending = null
                currentOnDenied()
            } else {
                dialog = outcome
            }
            else -> dialog = outcome
        }
    }

    // Back from the settings page: carry on if the permission is now on.
    LifecycleResumeEffect(awaitingSettings) {
        if (awaitingSettings) {
            awaitingSettings = false
            if (held()) {
                pending?.invoke()
            } else {
                currentOnDenied()
            }
            pending = null
        }
        onPauseOrDispose { }
    }

    dialog?.let { outcome ->
        val blocked = outcome == PermissionOutcome.BLOCKED
        AlertDialog(
            onDismissRequest = {
                dialog = null
                pending = null
                currentOnDenied()
            },
            title = { Text(stringResource(if (blocked) R.string.permission_blocked_title else R.string.permission_denied_title)) },
            text = {
                Text(if (blocked) "$rationale\n\n${stringResource(R.string.permission_blocked_hint)}" else rationale)
            },
            confirmButton = {
                TextButton(onClick = {
                    dialog = null
                    if (blocked) {
                        awaitingSettings = true
                        context.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    } else {
                        launcher.launch(permissions.toTypedArray())
                    }
                }) {
                    Text(stringResource(if (blocked) R.string.permission_open_settings else R.string.permission_ask_again))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    dialog = null
                    pending = null
                    currentOnDenied()
                }) { Text(stringResource(R.string.permission_later)) }
            },
        )
    }

    return remember(permissions, anyOf) {
        PermissionRequest(
            isGranted = ::held,
            ask = { onGranted ->
                pending = onGranted
                launcher.launch(permissions.toTypedArray())
            },
        )
    }
}

/** Which permissions the app has asked for before, so a first dismissal is not taken for "blocked". */
private object AskedBefore {
    private fun prefs(context: Context) = context.getSharedPreferences("permissions", Context.MODE_PRIVATE)
    fun get(context: Context, permission: String) = prefs(context).getBoolean(permission, false)
    fun set(context: Context, permission: String) = prefs(context).edit { putBoolean(permission, true) }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
