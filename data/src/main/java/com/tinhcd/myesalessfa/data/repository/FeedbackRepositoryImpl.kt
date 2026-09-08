package com.tinhcd.myesalessfa.data.repository

import com.tinhcd.myesalessfa.data.remote.dto.AuditPhotoPayload
import com.tinhcd.myesalessfa.data.remote.dto.FeedbackAudioPayload
import com.tinhcd.myesalessfa.data.remote.dto.FeedbackPayload
import com.tinhcd.myesalessfa.data.remote.http.orThrow
import com.tinhcd.myesalessfa.data.remote.service.FeedbackService
import com.tinhcd.myesalessfa.data.remote.storage.PhotoUploader
import com.tinhcd.myesalessfa.data.remote.storage.VisitBucket
import com.tinhcd.myesalessfa.data.session.SessionStore
import com.tinhcd.myesalessfa.domain.DataResult
import com.tinhcd.myesalessfa.domain.model.DraftFeedback
import com.tinhcd.myesalessfa.domain.repository.FeedbackRepository
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Media first, then the row — the same ordering as the display audit, for the same
 * reason. `submit_feedback` checks that every storage path it is handed actually
 * exists, so a row pointing at a missing object can never be written.
 *
 * Photos go to the visit-photos bucket and recordings to visit-audio, which is the
 * split the storage policies and the two media tables already assume.
 */
@Singleton
class FeedbackRepositoryImpl @Inject constructor(
    private val service: FeedbackService,
    private val uploader: PhotoUploader,
    private val session: SessionStore,
) : FeedbackRepository {

    override suspend fun submit(feedback: DraftFeedback): DataResult<Unit> = try {
        // The rep owns the storage folder the policies authorise on, so without a
        // session there is nowhere legitimate to put the files. Read once, and only
        // when there is something to upload.
        val salespersonId by lazy {
            session.current.value?.id
                ?: error("no signed-in salesperson to attribute the media to")
        }

        val photos = feedback.photos.map { photo ->
            AuditPhotoPayload(
                storagePath = uploader.upload(
                    salespersonId = salespersonId,
                    visitId = feedback.visitId,
                    localPath = photo.localPath,
                ),
                takenAt = Instant.ofEpochMilli(photo.takenAtEpochMs)
                    .atOffset(ZoneOffset.UTC).toString(),
                lat = photo.lat,
                lng = photo.lng,
                fileSize = photo.sizeBytes,
            )
        }

        val audios = feedback.recordings.map { clip ->
            FeedbackAudioPayload(
                storagePath = uploader.upload(
                    salespersonId = salespersonId,
                    visitId = feedback.visitId,
                    localPath = clip.localPath,
                    bucket = VisitBucket.AUDIO,
                ),
                seconds = clip.seconds,
                recordedAt = Instant.ofEpochMilli(clip.recordedAtEpochMs)
                    .atOffset(ZoneOffset.UTC).toString(),
                fileSize = clip.sizeBytes,
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
                photos = photos,
                audios = audios,
                clientCreatedAt = OffsetDateTime.now(ZoneOffset.UTC).toString(),
            ),
        ).orThrow()

        // Only now, and from the draft rather than the payload: the payload carries
        // storage object names, not paths on this device. Deleting earlier would
        // leave a failed submit with nothing to re-upload.
        feedback.photos.forEach { uploader.deleteLocal(it.localPath) }
        feedback.recordings.forEach { uploader.deleteLocal(it.localPath) }

        DataResult.Success(Unit)
    } catch (e: Exception) {
        DataResult.Failure(e.toAppError())
    }
}
