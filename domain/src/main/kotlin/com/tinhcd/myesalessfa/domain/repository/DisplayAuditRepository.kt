package com.tinhcd.myesalessfa.domain.repository

import com.tinhcd.myesalessfa.domain.DataResult
import com.tinhcd.myesalessfa.domain.model.DraftDisplayAudit

interface DisplayAuditRepository {
    /**
     * Sends [audit], photos and all. The photos are uploaded first and the row
     * written second, so a stored audit always has its evidence behind it.
     */
    suspend fun submit(audit: DraftDisplayAudit): DataResult<Unit>
}
