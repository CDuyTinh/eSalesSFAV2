package com.tinhcd.myesalessfa.feature.nfc

import javax.inject.Inject
import javax.inject.Singleton

/**
 * The tag that just opened a check-in from the route list, handed across so
 * the rep is not asked to tap the same sticker twice.
 *
 * Held in memory, not passed as a navigation argument: it is only good for
 * the moment between the tap and the screen it opened, and a stale uid
 * surviving in a back stack restored hours later would vouch for a visit it
 * had nothing to do with. Taken once, and only by the customer it belongs to.
 */
@Singleton
class RecentNfcTap @Inject constructor() {

    private data class Tap(val customerId: String, val uid: String, val atMs: Long)

    private var tap: Tap? = null

    fun record(customerId: String, uid: String, nowMs: Long = System.currentTimeMillis()) {
        tap = Tap(customerId, uid, nowMs)
    }

    /** The uid tapped for [customerId] within the last minute, cleared on reading. */
    fun take(customerId: String, nowMs: Long = System.currentTimeMillis()): String? {
        val t = tap ?: return null
        tap = null
        return t.uid.takeIf { t.customerId == customerId && nowMs - t.atMs < FRESH_MS }
    }

    private companion object {
        const val FRESH_MS = 60_000L
    }
}
