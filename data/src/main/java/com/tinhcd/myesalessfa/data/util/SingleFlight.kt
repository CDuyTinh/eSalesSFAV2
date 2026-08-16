package com.tinhcd.myesalessfa.data.util

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Collapses overlapping calls into one.
 *
 * A caller arriving while a call is already in the air joins that one and gets its
 * result; a caller arriving after it settles starts a fresh one. This is not a
 * cache — nothing is held once the call finishes — it only removes the duplicate
 * request two callers make when they react to the same event.
 *
 * The work runs on [scope] rather than on whichever caller happened to arrive
 * first, so one caller giving up does not cancel the request the other is still
 * waiting on.
 *
 * Note that the first caller's [block] is the one that runs; a second caller
 * passing a different block would silently get the first one's result. Every use
 * here passes the same block, which is what makes that acceptable.
 */
class SingleFlight<T>(parent: CoroutineScope) {

    /**
     * A supervisor child of [parent], not [parent] itself.
     *
     * An `async` that throws cancels its parent job, and the parent here is the
     * application scope — one failed request would have taken the session
     * collector down with it and left the app quietly deaf to sign-outs. Under a
     * supervisor the failure travels to `await()` and nowhere else. Cancelling
     * [parent] still cancels this, so nothing outlives it.
     */
    private val scope = CoroutineScope(
        parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]),
    )

    private var inFlight: Deferred<T>? = null
    private val mutex = Mutex()

    suspend fun run(block: suspend () -> T): T {
        val call = mutex.withLock {
            inFlight ?: scope.async { block() }.also { inFlight = it }
        }
        return try {
            call.await()
        } finally {
            // Identity-checked: a call that has already been replaced must not
            // clear its successor's slot.
            mutex.withLock {
                if (inFlight === call) inFlight = null
            }
        }
    }
}
