package com.tinhcd.myesalessfa.domain.repository

import com.tinhcd.myesalessfa.domain.DataResult
import com.tinhcd.myesalessfa.domain.model.CheckInPolicy
import com.tinhcd.myesalessfa.domain.model.CheckInRequest
import com.tinhcd.myesalessfa.domain.model.ReasonCode
import com.tinhcd.myesalessfa.domain.model.ReasonKind
import com.tinhcd.myesalessfa.domain.model.RouteStop
import com.tinhcd.myesalessfa.domain.model.Salesperson
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

interface AuthRepository {
    /** Emits the current rep, or null when signed out. */
    val currentUser: Flow<Salesperson?>

    /**
     * @param username the code the rep types, e.g. "nvbh01". Mapping it onto
     * whatever the auth backend wants is this layer's problem, not the UI's.
     */
    suspend fun signIn(username: String, password: String): DataResult<Salesperson>

    suspend fun signOut(): DataResult<Unit>
}

interface RouteRepository {
    /** Stops scheduled for [date], in visit order. */
    suspend fun getRoute(date: LocalDate): DataResult<List<RouteStop>>

    suspend fun getStop(customerId: String, date: LocalDate): DataResult<RouteStop?>
}

/** Outcome of handing a check-in to the data layer. */
enum class SubmitOutcome {
    /** Reached the server. */
    SENT,

    /** Stored locally; the outbox worker will retry. */
    QUEUED,
}

interface CheckInRepository {
    /**
     * Records a check-in. Succeeds even with no connection: the entry is
     * queued locally and flushed later, because a rep standing in a shop with
     * one bar of signal must not lose the visit.
     */
    suspend fun checkIn(request: CheckInRequest): DataResult<SubmitOutcome>

    suspend fun checkOut(visitId: String): DataResult<SubmitOutcome>

    /** Entries still waiting to reach the server. */
    val pendingCount: Flow<Int>
}

interface ConfigRepository {
    suspend fun checkInPolicy(): CheckInPolicy

    suspend fun reasons(kind: ReasonKind): List<ReasonCode>

    /** Pulls settings, reason codes and translations into the local cache. */
    suspend fun refresh(): DataResult<Unit>
}
