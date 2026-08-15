package com.tinhcd.myesalessfa.domain.repository

import com.tinhcd.myesalessfa.domain.DataResult
import com.tinhcd.myesalessfa.domain.model.DraftFeedback

interface FeedbackRepository {
    /**
     * Sends [feedback], audio and all. Any recording is uploaded first and the row
     * written second, so a stored row always has its audio behind it. The server
     * marks the `feedback` step done in the same transaction.
     */
    suspend fun submit(feedback: DraftFeedback): DataResult<Unit>
}
