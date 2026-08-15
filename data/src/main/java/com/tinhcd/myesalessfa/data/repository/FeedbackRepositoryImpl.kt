package com.tinhcd.myesalessfa.data.repository

import com.tinhcd.myesalessfa.data.remote.FeedbackApi
import com.tinhcd.myesalessfa.data.remote.FeedbackPayload
import com.tinhcd.myesalessfa.domain.DataResult
import com.tinhcd.myesalessfa.domain.model.DraftFeedback
import com.tinhcd.myesalessfa.domain.repository.FeedbackRepository
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FeedbackRepositoryImpl @Inject constructor(
    private val feedbackApi: FeedbackApi,
) : FeedbackRepository {

    override suspend fun submit(feedback: DraftFeedback): DataResult<Unit> = try {
        feedbackApi.submit(
            FeedbackPayload(
                // The idempotency key `submit_feedback` conflicts on, so a retry
                // after a timeout that in fact succeeded does not delete and rewrite
                // the row.
                id = UUID.randomUUID().toString(),
                visitId = feedback.visitId,
                feedbackDate = LocalDate.now().toString(),
                topicId = feedback.topicId,
                note = feedback.trimmedNote,
                // Swapped for the storage object name by the api on the way out.
                localAudioPath = feedback.audioPath,
                audioPath = null,
                audioSeconds = feedback.audioSeconds.takeIf { it > 0 },
                clientCreatedAt = OffsetDateTime.now(ZoneOffset.UTC).toString(),
            ),
        )
        DataResult.Success(Unit)
    } catch (e: Exception) {
        DataResult.Failure(e.toAppError())
    }
}
