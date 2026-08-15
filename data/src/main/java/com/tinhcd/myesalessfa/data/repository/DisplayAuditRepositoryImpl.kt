package com.tinhcd.myesalessfa.data.repository

import com.tinhcd.myesalessfa.data.remote.dto.AuditPhotoPayload
import com.tinhcd.myesalessfa.data.remote.dto.DisplayAuditPayload
import com.tinhcd.myesalessfa.data.remote.http.orThrow
import com.tinhcd.myesalessfa.data.remote.service.DisplayAuditService
import com.tinhcd.myesalessfa.data.remote.storage.PhotoUploader
import com.tinhcd.myesalessfa.data.session.SessionStore
import com.tinhcd.myesalessfa.domain.DataResult
import com.tinhcd.myesalessfa.domain.model.DraftDisplayAudit
import com.tinhcd.myesalessfa.domain.repository.DisplayAuditRepository
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bytes first, then the row.
 *
 * The order matters and is not interchangeable. `submit_display_audit` verifies that
 * every storage path it is given actually exists, so writing the row first would
 * simply be refused — which is the point. Storage and the database are separate
 * systems, and this sequence is what makes "the row exists" mean "the photo exists".
 */
@Singleton
class DisplayAuditRepositoryImpl @Inject constructor(
    private val service: DisplayAuditService,
    private val uploader: PhotoUploader,
    private val session: SessionStore,
) : DisplayAuditRepository {

    override suspend fun submit(audit: DraftDisplayAudit): DataResult<Unit> = try {
        // The rep owns the storage folder the policies authorise on, so without a
        // session there is no path to upload to. Failing here beats uploading
        // somewhere unowned.
        val salespersonId = session.current.value?.id
            ?: error("no signed-in salesperson to attribute the photos to")

        val uploaded = audit.photos.map { photo ->
            AuditPhotoPayload(
                storagePath = uploader.upload(
                    salespersonId = salespersonId,
                    visitId = audit.visitId,
                    localPath = photo.localPath,
                ),
                takenAt = Instant.ofEpochMilli(photo.takenAtEpochMs)
                    .atOffset(ZoneOffset.UTC).toString(),
                lat = photo.lat,
                lng = photo.lng,
                fileSize = photo.sizeBytes,
            )
        }

        service.submitDisplayAudit(
            DisplayAuditPayload(
                // The idempotency key `submit_display_audit` conflicts on, so a
                // retry after a timeout that in fact succeeded does not delete and
                // rewrite the audit.
                id = UUID.randomUUID().toString(),
                visitId = audit.visitId,
                auditDate = LocalDate.now().toString(),
                note = audit.note.trim().ifBlank { null },
                clientCreatedAt = OffsetDateTime.now(ZoneOffset.UTC).toString(),
                photos = uploaded,
            ),
        ).orThrow()

        // Only now, and from the draft rather than the payload: the payload carries
        // storage object names, not paths on this device. Deleting earlier would
        // leave a failed submit with nothing to re-upload.
        audit.photos.forEach { uploader.deleteLocal(it.localPath) }

        DataResult.Success(Unit)
    } catch (e: Exception) {
        DataResult.Failure(e.toAppError())
    }
}
