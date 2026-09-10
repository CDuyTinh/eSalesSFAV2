package com.tinhcd.myesalessfa.domain.repository

import com.tinhcd.myesalessfa.domain.DataResult
import com.tinhcd.myesalessfa.domain.model.DraftNearExpiry
import com.tinhcd.myesalessfa.domain.model.NearExpiryLot

interface NearExpiryRepository {

    /**
     * The lots this visit already wrote down, or an empty list when it has
     * written none. Null and empty are the same to the caller here: the step
     * always opens on a form, and what matters is which lines are already on it.
     */
    suspend fun lotsFor(visitId: String): DataResult<List<NearExpiryLot>>

    /**
     * Files the document. Photos are uploaded first and the rows written second,
     * so a stored check always has its evidence behind it.
     */
    suspend fun submit(draft: DraftNearExpiry): DataResult<Unit>
}
