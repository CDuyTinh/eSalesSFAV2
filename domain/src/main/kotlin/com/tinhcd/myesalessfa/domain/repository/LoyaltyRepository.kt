package com.tinhcd.myesalessfa.domain.repository

import com.tinhcd.myesalessfa.domain.DataResult
import com.tinhcd.myesalessfa.domain.model.LoyaltySnapshot

interface LoyaltyRepository {

    /**
     * What this outlet is accumulating towards and what it could still join, in
     * one read. The rep asks both questions at once, and fetching them apart
     * would show one of them stale.
     */
    suspend fun load(customerId: String): DataResult<LoyaltySnapshot>

    /**
     * Signs the outlet up at a band, pending head office, spending one of the
     * rep's slots. Refused where the window has closed, the slots are gone, or
     * the outlet is already in the programme.
     */
    suspend fun register(
        visitId: String,
        programId: String,
        levelId: String,
        reason: String? = null,
    ): DataResult<Unit>
}
