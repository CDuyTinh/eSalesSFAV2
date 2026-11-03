package com.tinhcd.myesalessfa.data.push

import com.google.firebase.messaging.FirebaseMessaging
import com.tinhcd.myesalessfa.data.remote.dto.DeviceTokenDto
import com.tinhcd.myesalessfa.data.remote.http.orThrow
import com.tinhcd.myesalessfa.data.remote.service.DeviceTokenService
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Tells the server which phone to push to.
 *
 * Registered on every sign-in and session restore, and again whenever FCM
 * rotates the token (SfaMessagingService.onNewToken); removed on sign-out,
 * before the session ends, so a phone handed to someone else stops receiving
 * the previous rep's notifications.
 *
 * Failures are the caller's to ignore: a missed registration costs a
 * notification, never the sign-in it rides on.
 */
@Singleton
class PushTokens @Inject constructor(
    private val service: DeviceTokenService,
) {
    suspend fun register(token: String? = null) {
        service.register(DeviceTokenDto(token = token ?: currentToken(), app = APP)).orThrow()
    }

    suspend fun unregister() {
        service.unregister(DeviceTokenDto(token = currentToken())).orThrow()
    }

    private suspend fun currentToken(): String = suspendCancellableCoroutine { cont ->
        FirebaseMessaging.getInstance().token
            .addOnSuccessListener { cont.resume(it) }
            .addOnFailureListener { cont.resumeWithException(it) }
    }

    private companion object {
        const val APP = "sfa"
    }
}
