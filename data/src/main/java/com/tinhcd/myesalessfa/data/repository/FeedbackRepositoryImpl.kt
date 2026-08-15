package com.tinhcd.myesalessfa.data.repository

import android.content.Context
import com.tinhcd.myesalessfa.data.local.OutboxDao
import com.tinhcd.myesalessfa.data.local.OutboxEntity
import com.tinhcd.myesalessfa.data.outbox.FeedbackPayload
import com.tinhcd.myesalessfa.data.outbox.OutboxFlusher
import com.tinhcd.myesalessfa.data.outbox.OutboxWorker
import com.tinhcd.myesalessfa.domain.DataResult
import com.tinhcd.myesalessfa.domain.model.DraftFeedback
import com.tinhcd.myesalessfa.domain.repository.FeedbackRepository
import com.tinhcd.myesalessfa.domain.repository.SubmitOutcome
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private val json = Json { encodeDefaults = true; explicitNulls = false }

/**
 * Feedback goes through the outbox, recording included.
 *
 * Worth queueing for the same reason the display audit is: what the customer said is
 * not reconstructable later, and a rep who recorded it in a shop with no signal must
 * not lose it on the walk back to the bike.
 */
@Singleton
class FeedbackRepositoryImpl @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val outboxDao: OutboxDao,
    private val flusher: OutboxFlusher,
) : FeedbackRepository {

    override suspend fun submit(feedback: DraftFeedback): DataResult<SubmitOutcome> = try {
        // Minted before anything is sent: the idempotency key submit_feedback
        // conflicts on, so a replay after a timeout that in fact succeeded does not
        // delete and rewrite the row.
        val payload = FeedbackPayload(
            id = UUID.randomUUID().toString(),
            visitId = feedback.visitId,
            feedbackDate = LocalDate.now().toString(),
            topicId = feedback.topicId,
            note = feedback.trimmedNote,
            // Swapped for the storage object name by the flusher on the way out.
            localAudioPath = feedback.audioPath,
            audioPath = null,
            audioSeconds = feedback.audioSeconds.takeIf { it > 0 },
            clientCreatedAt = OffsetDateTime.now(ZoneOffset.UTC).toString(),
        )

        outboxDao.insert(
            OutboxEntity(
                type = OutboxEntity.TYPE_FEEDBACK,
                payload = json.encodeToString(payload),
                createdAt = System.currentTimeMillis(),
            ),
        )

        val drained = runCatching { flusher.flush() }.getOrDefault(false)
        if (drained) {
            DataResult.Success(SubmitOutcome.SENT)
        } else {
            OutboxWorker.enqueue(context)
            DataResult.Success(SubmitOutcome.QUEUED)
        }
    } catch (e: Exception) {
        DataResult.Failure(e.toAppError())
    }
}
