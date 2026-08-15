package com.tinhcd.myesalessfa.domain.model

/**
 * How the cached reference data is doing.
 *
 * Reference data is settings, workflow steps, reason codes, translations and the
 * priced catalogue — everything the app reads but never writes. It has to be on the
 * device because a rep works inside shops with no signal, which means there is always
 * a gap between what head office has configured and what the phone knows. This
 * reports that gap instead of leaving it invisible.
 */
data class SyncState(
    val syncing: Boolean = false,
    /** Null until a sync has succeeded in this process. */
    val lastSyncedAtEpochMs: Long? = null,
    /**
     * The last attempt failed. Not an error state: the previous cache is still in
     * place and every screen still works, so this is worth showing quietly rather
     * than blocking anything.
     */
    val lastAttemptFailed: Boolean = false,
)
