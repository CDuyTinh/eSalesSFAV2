package com.tinhcd.myesalessfa.domain.repository

import com.tinhcd.myesalessfa.domain.DataResult
import com.tinhcd.myesalessfa.domain.model.Salesperson
import com.tinhcd.myesalessfa.domain.model.SessionState
import kotlinx.coroutines.flow.Flow

interface AuthRepository {
    /**
     * Whether there is a session, and the rep's profile when it has been loaded.
     * Emits nothing until the stored session has been resolved one way or the
     * other, so a collector taking `first()` never acts on "not known yet".
     */
    val sessionState: Flow<SessionState>

    /**
     * The current rep, or null. Null is ambiguous by nature — signed out, or signed
     * in without a profile — so anything that has to tell those apart wants
     * [sessionState] instead. This stays for the callers that only need a name.
     */
    val currentUser: Flow<Salesperson?>

    /**
     * @param username the code the rep types, e.g. "nvbh01". Mapping it onto
     * whatever the auth backend wants is this layer's problem, not the UI's.
     */
    suspend fun signIn(username: String, password: String): DataResult<Salesperson>

    /**
     * Re-fetches the profile for a session that already exists, for the case where
     * the fetch at sign-in or at launch failed for its own reasons. Updates
     * [sessionState] on success.
     */
    suspend fun refreshProfile(): DataResult<Salesperson>

    suspend fun signOut(): DataResult<Unit>
}
