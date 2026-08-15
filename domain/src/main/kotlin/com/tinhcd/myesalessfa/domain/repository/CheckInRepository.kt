package com.tinhcd.myesalessfa.domain.repository

import com.tinhcd.myesalessfa.domain.DataResult
import com.tinhcd.myesalessfa.domain.model.CheckInRequest

interface CheckInRepository {
    /**
     * Records a check-in against the server. Fails if the request does not land, and
     * the rep is expected to retry — this app is online-only by design.
     */
    suspend fun checkIn(request: CheckInRequest): DataResult<Unit>

    suspend fun checkOut(visitId: String): DataResult<Unit>
}
