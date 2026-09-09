package com.tinhcd.myesalessfa.domain.repository

import com.tinhcd.myesalessfa.domain.DataResult
import com.tinhcd.myesalessfa.domain.model.DisplayProgram
import com.tinhcd.myesalessfa.domain.model.DisplayProgramOffer
import com.tinhcd.myesalessfa.domain.model.DraftDisplayAudit

interface DisplayAuditRepository {
    /**
     * The display programmes this outlet is audited on, with whatever this visit
     * has already scored. An empty list is a real answer, not a failure: an outlet
     * in no programme is audited on none.
     */
    suspend fun programs(customerId: String, visitId: String): DataResult<List<DisplayProgram>>

    /**
     * Sends [audit], photos and all. The photos are uploaded first and the row
     * written second, so a stored audit always has its evidence behind it.
     */
    suspend fun submit(audit: DraftDisplayAudit): DataResult<Unit>

    /**
     * Programmes this outlet could still join today. Empty is a real answer: an
     * outlet already in everything its branch runs has nothing left to join.
     */
    suspend fun openPrograms(customerId: String): DataResult<List<DisplayProgramOffer>>

    /**
     * Signs the outlet up at a level, pending head office, spending one of the
     * rep's slots. Refused where the window has closed, the slots are gone, or
     * the outlet is already in the programme.
     */
    suspend fun register(
        visitId: String,
        programId: String,
        levelId: String,
    ): DataResult<Unit>
}
