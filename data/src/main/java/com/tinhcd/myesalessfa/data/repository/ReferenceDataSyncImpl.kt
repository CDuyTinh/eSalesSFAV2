package com.tinhcd.myesalessfa.data.repository

import com.tinhcd.myesalessfa.data.di.ApplicationScope
import com.tinhcd.myesalessfa.domain.model.SessionState
import com.tinhcd.myesalessfa.domain.DataResult
import com.tinhcd.myesalessfa.domain.model.SyncState
import com.tinhcd.myesalessfa.domain.repository.AuthRepository
import com.tinhcd.myesalessfa.domain.repository.CatalogRepository
import com.tinhcd.myesalessfa.domain.repository.ConfigRepository
import com.tinhcd.myesalessfa.domain.repository.ReferenceDataSync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Refreshes the cached reference data, on a schedule chosen to be explicable rather
 * than clever.
 *
 * Once per app launch, once per calendar day while the app stays alive, and whenever
 * the rep asks. Those three rules cover the ways stale data actually bites: a phone
 * that has been in a pocket since yesterday, a rep who never signs out, and head
 * office ringing to say a price changed ten minutes ago.
 *
 * There is deliberately no minute-based staleness window and no persisted timestamp.
 * A window would be a number nobody could justify; persistence is unnecessary because
 * process start is itself a trigger, so the only thing worth remembering is whether
 * *this* process has synced today. If Android kills the app, the next launch syncs,
 * which is the behaviour a stored timestamp would have been protecting.
 */
@Singleton
class ReferenceDataSyncImpl @Inject constructor(
    private val configRepository: ConfigRepository,
    private val catalogRepository: CatalogRepository,
    private val authRepository: AuthRepository,
    @ApplicationScope private val scope: CoroutineScope,
) : ReferenceDataSync {

    private val _state = MutableStateFlow(SyncState())
    override val state: Flow<SyncState> = _state.asStateFlow()

    /**
     * Serialises attempts. Both refreshes replace their tables wholesale, so two
     * running at once would have them clearing and rewriting the same rows in an
     * order nobody chose — and a foreground event arriving while sign-in is still
     * fetching is an ordinary occurrence, not an edge case.
     */
    private val mutex = Mutex()

    /** In-memory by design; see the class comment. */
    private var lastSyncedDate: LocalDate? = null

    override fun syncIfStale() {
        scope.launch {
            // Gated on there being a session, and deliberately not on the rep's profile
            // having loaded. Two bugs were found here by watching a device, and both
            // came from getting this distinction wrong.
            //
            // The first version read a snapshot of the profile. The foreground event
            // fires the moment the process starts, long before the stored session has
            // been restored, so the snapshot was null every time and the cold-start
            // sync never ran at all.
            //
            // Waiting for the profile instead was still wrong: when /bootstrap fails
            // the session stays perfectly valid but no profile is ever published, so
            // the sync sat out the whole session. That was visible on screen — the
            // route had loaded over the same JWT while the app was reporting a missing
            // profile. These endpoints need a token, not a name.
            //
            // Bounded, because on the login screen no session is coming until the rep
            // types one, and sign-in does its own sync.
            val state = withTimeoutOrNull(SESSION_WAIT_MS) {
                authRepository.sessionState.first()
            }
            if (state !is SessionState.SignedIn) return@launch
            if (lastSyncedDate == LocalDate.now()) return@launch
            runSync()
        }
    }

    override suspend fun syncNow(): DataResult<Unit> = runSync()

    private suspend fun runSync(): DataResult<Unit> = mutex.withLock {
        _state.update { it.copy(syncing = true) }

        var failure: DataResult.Failure? = null

        // Retried, because the failure this most often hits is the network not being
        // ready yet rather than anything being wrong. Observed on a device: the very
        // first request after process start came back UnknownHostException while the
        // radio was still waking, and the same host resolved fine seconds later. One
        // attempt would have left the cache stale until the next foregrounding.
        //
        // Three tries over a few seconds, then stop. Anything still failing after that
        // is a real outage, and the rep has the previous cache plus a refresh button;
        // grinding away at it would only cost battery inside a shop with no signal.
        for (attempt in 0 until MAX_ATTEMPTS) {
            if (attempt > 0) delay(RETRY_DELAYS_MS[attempt - 1])

            // Both halves are attempted even if the first fails: they are independent
            // caches, and a catalogue that arrives while the settings call is timing
            // out is worth keeping.
            val config = configRepository.refresh()
            val catalog = catalogRepository.refresh()

            failure = (config as? DataResult.Failure) ?: (catalog as? DataResult.Failure)
            if (failure == null) break
        }

        if (failure == null) {
            lastSyncedDate = LocalDate.now()
            _state.update {
                it.copy(
                    syncing = false,
                    lastSyncedAtEpochMs = Instant.now().toEpochMilli(),
                    lastAttemptFailed = false,
                )
            }
            return@withLock DataResult.Success(Unit)
        }

        // Deliberately not recorded as synced. Marking a half-finished refresh as
        // done for today would leave whichever half failed stale until tomorrow,
        // which is the exact bug this class exists to remove.
        _state.update { it.copy(syncing = false, lastAttemptFailed = true) }
        failure
    }

    private companion object {
        /**
         * Long enough to cover restoring a session over a slow connection — the profile
         * fetch behind it allows twenty seconds to connect — and short enough that a
         * coroutine is not left waiting on a login that is not coming.
         */
        const val SESSION_WAIT_MS = 30_000L

        const val MAX_ATTEMPTS = 3

        /** Short enough that sign-in does not visibly stall on a dead connection. */
        val RETRY_DELAYS_MS = longArrayOf(1_500, 4_000)
    }
}
