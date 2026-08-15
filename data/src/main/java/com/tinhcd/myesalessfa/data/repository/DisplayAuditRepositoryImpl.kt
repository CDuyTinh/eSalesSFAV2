package com.tinhcd.myesalessfa.data.repository

import com.tinhcd.myesalessfa.data.remote.dto.AuditPhotoPayload
import com.tinhcd.myesalessfa.data.remote.api.DisplayAuditApi
import com.tinhcd.myesalessfa.data.remote.dto.DisplayAuditPayload
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
 * Photos travel as local paths; [DisplayAuditApi] uploads the bytes and writes the
 * row. The whole submit is one call from the screen's point of view, and a failure
 * anywhere in it is reported so the rep can try again before leaving the shop.
 */
@Singleton
class DisplayAuditRepositoryImpl @Inject constructor(
    private val displayAuditApi: DisplayAuditApi,
) : DisplayAuditRepository {

    override suspend fun submit(audit: DraftDisplayAudit): DataResult<Unit> = try {
        displayAuditApi.submit(
            DisplayAuditPayload(
                // The idempotency key `submit_display_audit` conflicts on, so a
                // retry after a timeout that in fact succeeded does not delete and
                // rewrite the audit.
                id = UUID.randomUUID().toString(),
                visitId = audit.visitId,
                auditDate = LocalDate.now().toString(),
                note = audit.note.trim().ifBlank { null },
                clientCreatedAt = OffsetDateTime.now(ZoneOffset.UTC).toString(),
                photos = audit.photos.map {
                    AuditPhotoPayload(
                        localPath = it.localPath,
                        // Filled in by the uploader on the way out; nothing has been
                        // uploaded yet.
                        storagePath = null,
                        takenAt = Instant.ofEpochMilli(it.takenAtEpochMs)
                            .atOffset(ZoneOffset.UTC).toString(),
                        lat = it.lat,
                        lng = it.lng,
                        fileSize = it.sizeBytes,
                    )
                },
            ),
        )
        DataResult.Success(Unit)
    } catch (e: Exception) {
        DataResult.Failure(e.toAppError())
    }
}
