package com.tinhcd.myesalessfa.push

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.tinhcd.myesalessfa.data.push.PushTokens
import com.tinhcd.myesalessfa.data.session.SessionStore
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/** FCM's entry point: a rotated token, and a message that arrived while the app was open. */
@AndroidEntryPoint
class SfaMessagingService : FirebaseMessagingService() {

    @Inject lateinit var pushTokens: PushTokens
    @Inject lateinit var session: SessionStore

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Signed out, there is nobody to file it against; the next sign-in registers it. */
    override fun onNewToken(token: String) {
        if (session.current.value == null) return
        scope.launch { runCatching { pushTokens.register(token) } }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val n = message.notification ?: return
        PushNotifications.show(this, n.title, n.body, message.data[PushNotifications.EXTRA_SCREEN])
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
