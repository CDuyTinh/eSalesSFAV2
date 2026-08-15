package com.tinhcd.myesalessfa.data.repository

import com.tinhcd.myesalessfa.data.remote.dto.FeedbackPayload
import com.tinhcd.myesalessfa.data.remote.http.orThrow
import com.tinhcd.myesalessfa.data.remote.service.FeedbackService
import com.tinhcd.myesalessfa.data.remote.storage.PhotoUploader
import com.tinhcd.myesalessfa.data.remote.storage.VisitBucket
import com.tinhcd.myesalessfa.data.session.SessionStore
import com.tinhcd.myesalessfa.domain.DataResult
import com.tinhcd.myesalessfa.domain.model.DraftFeedback
import com.tinhcd.myesalessfa.domain.repository.FeedbackRepository
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Audio first, then the row — the same ordering as the display audit, for the same
 * reason. `submit_feedback` checks that the storage path it is handed actually
 * exists, so a row pointing at a missing object can never be written.
 */
@Singleton
class FeedbackRepositoryImpl @Inject constructor(
    private val service: FeedbackService,
    private val uploader: PhotoUploader,
    private val session: SessionStore,
) : FeedbackRepository {

    override suspend fun submit(feedback: DraftFeedback): DataResult<Unit> = try {
        val localAudio = feedback.audioPath

        val audioPath = localAudio?.let {
            // The rep owns the storage folder the policies authorise on, so without
            // a session there is nowhere legitimate to put the file.
            val salespersonId = session.current.value?.id
                ?: error("no signed-in salesperson to attribute the recording to")

            uploader.upload(
                salespersonId = salespersonId,
                visitId = feedback.visitId,
                localPath = it,
                bucket = VisitBucket.AUDIO,
            )
        }

        service.submitFeedback(
            FeedbackPayload(
                // The idempotency key `submit_feedback` conflicts on, so a retry
                // after a timeout that in fact succeeded does not delete and rewrite
                // the row.
                id = UUID.randomUUID().toString(),
                visitId = feedback.visitId,
                feedbackDate = LocalDate.now().toString(),
                topicId = feedback.topicId,
                note = feedback.trimmedNote,
                audioPath = audioPath,
                audioSeconds = feedback.audioSeconds.takeIf { it > 0 },
                clientCreatedAt = OffsetDateTime.now(ZoneOffset.UTC).toString(),
            ),
        ).orThrow()

        // Only now, so a failed submit still has the bytes to retry with.
        localAudio?.let { uploader.deleteLocal(it) }

        DataResult.Success(Unit)
    } catch (e: Exception) {
        DataResult.Failure(e.toAppError())
    }
}
