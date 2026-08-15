package com.tinhcd.myesalessfa.domain.repository

import com.tinhcd.myesalessfa.domain.DataResult
import com.tinhcd.myesalessfa.domain.model.SyncState
import kotlinx.coroutines.flow.Flow

/**
 * Keeps the cached reference data current.
 *
 * It used to be refreshed in exactly one place — the sign-in screen — so a rep who
 * stayed signed in never saw a change head office made. That is not hypothetical: a
 * step's own `note_min_length` and a set of new feedback topics were each invisible on
 * a real device until someone signed out and back in, and both times the app was
 * confidently rendering stale rules.
 *
 * Refreshing is never allowed to matter to whoever asked for it. Every screen reads
 * from the cache, the cache is only replaced on success, and a rep inside a shop with
 * no signal must not be told that something is wrong when nothing is.
 */
interface ReferenceDataSync {
    val state: Flow<SyncState>

    /**
     * Refreshes if the cache has not been refreshed yet today, and does nothing
     * otherwise. Returns immediately — the work continues in the background, because
     * the caller is a lifecycle callback, not somebody waiting for an answer.
     */
    fun syncIfStale()

    /**
     * Refreshes now, whatever the cache's age, and waits for the outcome. For
     * sign-in, which must not hand a rep an app with no catalogue, and for the rep's
     * own refresh action, which exists precisely for when they have been told
     * something changed.
     */
    suspend fun syncNow(): DataResult<Unit>
}
