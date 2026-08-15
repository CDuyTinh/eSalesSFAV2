package com.tinhcd.myesalessfa.data.remote

import com.tinhcd.myesalessfa.data.outbox.FeedbackPayload
import com.tinhcd.myesalessfa.data.remote.http.orThrow
import com.tinhcd.myesalessfa.data.session.SessionStore
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Feedback submission: audio first, then the row.
 *
 * Same ordering as the display audit, for the same reason. `submit_feedback` checks
 * that the storage path it is handed actually exists, so writing the row first would
 * be refused — which is the point, since storage and the database are separate
 * systems and a row pointing at a missing object looks exactly like feedback with a
 * recording behind it.
 *
 * Re-uploading on a replay is harmless: the upload upserts on the same path and the
 * function is idempotent on the feedback id.
 */
@Singleton
class FeedbackApi @Inject constructor(
    private val service: FunctionsService,
    private val uploader: PhotoUploader,
    private val session: SessionStore,
) {
    suspend fun submit(payload: FeedbackPayload) {
        val localAudio = payload.localAudioPath

        val uploaded = if (localAudio == null) {
            payload
        } else {
            // The rep owns the storage folder the policies authorise on, so without a
            // session there is nowhere legitimate to put the file. Failing here keeps
            // the entry queued rather than uploading somewhere unowned.
            val salespersonId = session.current.value?.id
                ?: error("no signed-in salesperson to attribute the recording to")

            payload.copy(
                audioPath = uploader.upload(
                    salespersonId = salespersonId,
                    visitId = payload.visitId,
                    localPath = localAudio,
                    bucket = VisitBucket.AUDIO,
                ),
            )
        }

        service.submitFeedback(uploaded).orThrow()

        // Only now. Deleting earlier would leave a queued retry with nothing to
        // upload, and the feedback could never be delivered.
        localAudio?.let { uploader.deleteLocal(it) }
    }
}
