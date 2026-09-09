package com.tinhcd.myesalessfa.data.repository

import com.tinhcd.myesalessfa.data.remote.dto.AuditPhotoPayload
import com.tinhcd.myesalessfa.data.remote.dto.PosmCheckPayload
import com.tinhcd.myesalessfa.data.remote.dto.PosmMovementLinePayload
import com.tinhcd.myesalessfa.data.remote.dto.PosmMovementPayload
import com.tinhcd.myesalessfa.data.remote.dto.PosmRegistrationLinePayload
import com.tinhcd.myesalessfa.data.remote.dto.PosmRegistrationPayload
import com.tinhcd.myesalessfa.data.remote.http.orThrow
import com.tinhcd.myesalessfa.data.remote.service.PosmService
import com.tinhcd.myesalessfa.data.remote.storage.PhotoUploader
import com.tinhcd.myesalessfa.data.session.SessionStore
import com.tinhcd.myesalessfa.domain.DataResult
import com.tinhcd.myesalessfa.domain.model.DraftPosmCheck
import com.tinhcd.myesalessfa.domain.model.DraftPosmMovement
import com.tinhcd.myesalessfa.domain.model.DraftPosmRegistration
import com.tinhcd.myesalessfa.domain.model.PosmAtCustomer
import com.tinhcd.myesalessfa.domain.model.PosmCatalogueEntry
import com.tinhcd.myesalessfa.domain.model.PosmCondition
import com.tinhcd.myesalessfa.domain.model.PosmRegistration
import com.tinhcd.myesalessfa.domain.repository.PosmRepository
import com.tinhcd.myesalessfa.domain.repository.PosmSnapshot
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bytes first, then the row — the same order the display audit uses, and for the
 * same reason: `submit_posm_check` verifies that every storage path it is given
 * exists, so writing the row first would simply be refused.
 */
@Singleton
class PosmRepositoryImpl @Inject constructor(
    private val service: PosmService,
    private val uploader: PhotoUploader,
    private val session: SessionStore,
) : PosmRepository {

    override suspend fun load(
        customerId: String,
        visitId: String,
    ): DataResult<PosmSnapshot> = try {
        val body = service.load(customerId, visitId).orThrow()

        DataResult.Success(
            PosmSnapshot(
                placed = body.placed.map {
                    PosmAtCustomer(
                        programId = it.programId,
                        programCode = it.programCode,
                        programName = it.programName,
                        itemId = it.itemId,
                        itemCode = it.itemCode,
                        itemName = it.itemName,
                        unitName = it.unitName,
                        imageUrl = it.imageUrl,
                        placedQty = it.placedQty,
                        countedQty = it.countedQty,
                        // An unrecognised code reads as unchecked rather than
                        // crashing: a server that gains a fourth condition should
                        // not take the step down with it.
                        condition = PosmCondition.fromWire(it.condition),
                        remark = it.remark,
                        suggestion = it.suggestion,
                        photoCount = it.photoCount,
                    )
                },
                catalogue = body.catalogue.map {
                    PosmCatalogueEntry(
                        programId = it.programId,
                        programCode = it.programCode,
                        programName = it.programName,
                        itemId = it.itemId,
                        itemCode = it.itemCode,
                        itemName = it.itemName,
                        unitName = it.unitName,
                        imageUrl = it.imageUrl,
                        maxPerCustomer = it.maxPerCustomer,
                        registeredQty = it.registeredQty,
                        approvedQty = it.approvedQty,
                        deliveredQty = it.deliveredQty,
                        placedQty = it.placedQty,
                        registrationStatus = it.registrationStatus,
                    )
                },
                registrations = body.registrations.map {
                    PosmRegistration(
                        programId = it.programId,
                        programCode = it.programCode,
                        programName = it.programName,
                        itemId = it.itemId,
                        itemCode = it.itemCode,
                        itemName = it.itemName,
                        unitName = it.unitName,
                        registeredQty = it.regisQty,
                        approvedQty = it.approvedQty,
                        deliveredQty = it.deliveredQty,
                        status = it.status,
                        registeredAt = it.registeredAt,
                    )
                },
            ),
        )
    } catch (e: Exception) {
        DataResult.Failure(e.toAppError())
    }

    override suspend fun submit(check: DraftPosmCheck): DataResult<Unit> = try {
        // The rep owns the storage folder the policies authorise on, so without a
        // session there is no path to upload to. Failing here beats uploading
        // somewhere unowned.
        val salespersonId = session.current.value?.id
            ?: error("no signed-in salesperson to attribute the photos to")

        val uploaded = check.photos.map { photo ->
            AuditPhotoPayload(
                storagePath = uploader.upload(
                    salespersonId = salespersonId,
                    visitId = check.visitId,
                    localPath = photo.localPath,
                ),
                takenAt = Instant.ofEpochMilli(photo.takenAtEpochMs)
                    .atOffset(ZoneOffset.UTC).toString(),
                lat = photo.lat,
                lng = photo.lng,
                fileSize = photo.sizeBytes,
            )
        }

        service.submitCheck(
            PosmCheckPayload(
                // The idempotency key `submit_posm_check` returns early on, so a
                // retry after a timeout that in fact succeeded does not delete and
                // rewrite the check.
                id = UUID.randomUUID().toString(),
                visitId = check.visitId,
                programId = check.item.programId,
                itemId = check.item.itemId,
                countedQty = check.countedQty,
                condition = check.condition?.wireValue,
                remark = check.remark.trim().ifBlank { null },
                suggestion = check.suggestion.trim().ifBlank { null },
                checkDate = LocalDate.now().toString(),
                clientCreatedAt = OffsetDateTime.now(ZoneOffset.UTC).toString(),
                photos = uploaded,
            ),
        ).orThrow()

        // Only now, and from the draft rather than the payload: the payload carries
        // storage object names, not paths on this device. Deleting earlier would
        // leave a failed submit with nothing to re-upload.
        check.photos.forEach { uploader.deleteLocal(it.localPath) }

        DataResult.Success(Unit)
    } catch (e: Exception) {
        DataResult.Failure(e.toAppError())
    }

    override suspend fun register(draft: DraftPosmRegistration): DataResult<Unit> = try {
        service.register(
            PosmRegistrationPayload(
                visitId = draft.visitId,
                lines = draft.askedLines.map { line ->
                    PosmRegistrationLinePayload(
                        programId = line.entry.programId,
                        itemId = line.entry.itemId,
                        qty = line.qty,
                        reason = draft.reason.trim().ifBlank { null },
                    )
                },
            ),
        ).orThrow()

        DataResult.Success(Unit)
    } catch (e: Exception) {
        DataResult.Failure(e.toAppError())
    }

    override suspend fun move(draft: DraftPosmMovement): DataResult<Unit> = try {
        // The rep owns the storage folder the policies authorise on, so without a
        // session there is nowhere legitimate to put the evidence.
        val salespersonId = session.current.value?.id
            ?: error("no signed-in salesperson to attribute the photos to")

        val uploaded = draft.photos.map { photo ->
            AuditPhotoPayload(
                storagePath = uploader.upload(
                    salespersonId = salespersonId,
                    visitId = draft.visitId,
                    localPath = photo.localPath,
                ),
                takenAt = Instant.ofEpochMilli(photo.takenAtEpochMs)
                    .atOffset(ZoneOffset.UTC).toString(),
                lat = photo.lat,
                lng = photo.lng,
                fileSize = photo.sizeBytes,
            )
        }

        service.move(
            PosmMovementPayload(
                // The idempotency key `submit_posm_movement` returns early on, so a
                // retry after a timeout that in fact succeeded does not hand the
                // shop a second fridge.
                id = UUID.randomUUID().toString(),
                visitId = draft.visitId,
                kind = draft.kind.wireValue,
                note = draft.note.trim().ifBlank { null },
                clientCreatedAt = OffsetDateTime.now(ZoneOffset.UTC).toString(),
                lines = draft.movingLines.map { line ->
                    PosmMovementLinePayload(
                        programId = line.programId,
                        itemId = line.itemId,
                        qty = line.qty,
                    )
                },
                photos = uploaded,
            ),
        ).orThrow()

        // Only now, and from the draft rather than the payload: the payload carries
        // storage object names, not paths on this device.
        draft.photos.forEach { uploader.deleteLocal(it.localPath) }

        DataResult.Success(Unit)
    } catch (e: Exception) {
        DataResult.Failure(e.toAppError())
    }

    override suspend fun completeEmpty(visitId: String): DataResult<Unit> = try {
        // No asset, no photos, no count. The server refuses this shape unless the
        // outlet really holds nothing, so a stale list cannot mark a step done
        // over assets that are still there.
        service.submitCheck(
            PosmCheckPayload(
                id = UUID.randomUUID().toString(),
                visitId = visitId,
                clientCreatedAt = OffsetDateTime.now(ZoneOffset.UTC).toString(),
            ),
        ).orThrow()

        DataResult.Success(Unit)
    } catch (e: Exception) {
        DataResult.Failure(e.toAppError())
    }
}
