package com.tinhcd.myesalessfa.push

import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The screen a tapped notification asked for, held until the signed-in app can
 * open it. Set from the intent that opened the activity — FCM's own for a push
 * shown in the background, [PushNotifications]' for one shown while open; both
 * carry it as [PushNotifications.EXTRA_SCREEN].
 */
object PushRoute {
    private val _pending = MutableStateFlow<String?>(null)
    val pending: StateFlow<String?> = _pending.asStateFlow()

    /** Taken out of the intent once read, so rotating the screen does not open it twice. */
    fun from(intent: Intent?) {
        val screen = intent?.getStringExtra(PushNotifications.EXTRA_SCREEN)?.takeIf { it.isNotBlank() } ?: return
        intent.removeExtra(PushNotifications.EXTRA_SCREEN)
        _pending.value = screen
    }

    fun consume() {
        _pending.value = null
    }
}
