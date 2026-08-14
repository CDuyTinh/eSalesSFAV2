package com.tinhcd.myesalessfa.data.repository

import android.content.Context
import com.tinhcd.myesalessfa.data.local.OutboxDao
import com.tinhcd.myesalessfa.data.local.OutboxEntity
import com.tinhcd.myesalessfa.data.outbox.AuditPhotoPayload
import com.tinhcd.myesalessfa.data.outbox.DisplayAuditPayload
import com.tinhcd.myesalessfa.data.outbox.OutboxFlusher
import com.tinhcd.myesalessfa.data.outbox.OutboxWorker
import com.tinhcd.myesalessfa.domain.DataResult
import com.tinhcd.myesalessfa.domain.model.DraftDisplayAudit
import com.tinhcd.myesalessfa.domain.repository.DisplayAuditRepository
import com.tinhcd.myesalessfa.domain.repository.SubmitOutcome
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private val json = Json { encodeDefaults = true; explicitNulls = false }

/**
 * Display audits go through the outbox, photos included.
 *
 * This is the entry the outbox was always going to need most: the bytes are already
 * on the device, they are the evidence, and a rep who photographed a shelf in a dead
 * spot cannot go back and photograph it again from the car park.
 */
@Singleton
class DisplayAuditRepositoryImpl @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val outboxDao: OutboxDao,
    private val flusher: OutboxFlusher,
) : DisplayAuditRepository {

    override suspend fun submit(audit: DraftDisplayAudit): DataResult<SubmitOutcome> = try {
        // Minted before anything is sent: the idempotency key submit_display_audit
        // conflicts on, so a replay after a timeout that in fact succeeded does not
        // delete and rewrite the audit.
        val auditId = UUID.randomUUID().toString()

        val payload = DisplayAuditPayload(
            id = auditId,
            visitId = audit.visitId,
            auditDate = LocalDate.now().toString(),
            note = audit.note.trim().ifBlank { null },
            clientCreatedAt = OffsetDateTime.now(ZoneOffset.UTC).toString(),
            photos = audit.photos.map {
                AuditPhotoPayload(
                    localPath = it.localPath,
                    // Filled in by the uploader on the way out; there is no storage
                    // path yet because nothing has been uploaded.
                    storagePath = null,
                    takenAt = Instant.ofEpochMilli(it.takenAtEpochMs)
                        .atOffset(ZoneOffset.UTC).toString(),
                    lat = it.lat,
                    lng = it.lng,
                    fileSize = it.sizeBytes,
                )
            },
        )

        outboxDao.insert(
            OutboxEntity(
                type = OutboxEntity.TYPE_DISPLAY_AUDIT,
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
