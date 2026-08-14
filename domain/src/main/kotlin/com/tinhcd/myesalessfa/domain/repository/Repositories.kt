package com.tinhcd.myesalessfa.domain.repository

import com.tinhcd.myesalessfa.domain.DataResult
import com.tinhcd.myesalessfa.domain.model.CheckInRequest
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
}

interface CheckInRepository {
    /**
     * Records a check-in. Succeeds even with no connection: the entry is
     * queued locally and flushed later, because a rep standing in a shop with
     * one bar of signal must not lose the visit.
     */
    suspend fun checkIn(request: CheckInRequest): DataResult<Unit>

    suspend fun checkOut(visitId: String, photoPath: String?): DataResult<Unit>
}

interface ConfigRepository {
    suspend fun setting(key: String): String?
    suspend fun translate(key: String): String
    suspend fun refresh(): DataResult<Unit>
}
