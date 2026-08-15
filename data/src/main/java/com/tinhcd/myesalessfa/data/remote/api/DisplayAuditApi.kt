package com.tinhcd.myesalessfa.data.remote.api

import com.tinhcd.myesalessfa.data.remote.dto.DisplayAuditPayload
import com.tinhcd.myesalessfa.data.remote.service.DisplayAuditService

import com.tinhcd.myesalessfa.data.remote.http.orThrow
import com.tinhcd.myesalessfa.data.session.SessionStore
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Display audit submission: bytes first, then the row.
 *
 * The order matters and is not interchangeable. `submit_display_audit` verifies that
 * every storage path it is given actually exists, so writing the row first would
 * simply be refused â€” which is the point. Storage and the database are separate
 * systems, and this sequence is what makes "the row exists" mean "the photo exists".
 *
 * Re-uploading on a replay is harmless: the upload upserts on the same path, and the
 * function is idempotent on the audit id.
 */
@Singleton
class DisplayAuditApi @Inject constructor(
    private val service: DisplayAuditService,
    private val uploader: PhotoUploader,
    private val session: SessionStore,
) {
    suspend fun submit(payload: DisplayAuditPayload) {
        // The rep owns the storage folder the policies authorise on, so without a
        // session there is no path to upload to. Failing here keeps the entry queued
        // until the session is back rather than uploading somewhere unowned.
        val salespersonId = session.current.value?.id
            ?: error("no signed-in salesperson to attribute the photos to")

        val uploaded = payload.photos.map { photo ->
            photo.copy(
                storagePath = uploader.upload(
                    salespersonId = salespersonId,
                    visitId = payload.visitId,
                    localPath = photo.localPath,
                ),
            )
        }

        service.submitDisplayAudit(payload.copy(photos = uploaded)).orThrow()

        // Only now. Deleting earlier would leave a queued retry with nothing to
        // upload, and the audit could never be delivered.
        uploaded.forEach { uploader.deleteLocal(it.localPath) }
    }
}
