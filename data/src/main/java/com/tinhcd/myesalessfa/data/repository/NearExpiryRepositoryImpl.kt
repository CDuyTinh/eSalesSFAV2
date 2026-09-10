package com.tinhcd.myesalessfa.data.repository

import com.tinhcd.myesalessfa.data.remote.dto.AuditPhotoPayload
import com.tinhcd.myesalessfa.data.remote.dto.NearExpiryLotPayload
import com.tinhcd.myesalessfa.data.remote.dto.NearExpiryPayload
import com.tinhcd.myesalessfa.data.remote.http.orThrow
import com.tinhcd.myesalessfa.data.remote.service.NearExpiryService
import com.tinhcd.myesalessfa.data.remote.storage.PhotoUploader
import com.tinhcd.myesalessfa.data.session.SessionStore
import com.tinhcd.myesalessfa.domain.DataResult
import com.tinhcd.myesalessfa.domain.model.DraftNearExpiry
import com.tinhcd.myesalessfa.domain.model.NearExpiryLot
import com.tinhcd.myesalessfa.domain.repository.NearExpiryRepository
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bytes first, then the rows — the order every evidence-taking step here uses.
 * `submit_near_expiry_check` verifies that each storage path exists, so a row
 * written first would simply be refused.
 */
@Singleton
class NearExpiryRepositoryImpl @Inject constructor(
    private val service: NearExpiryService,
    private val uploader: PhotoUploader,
    private val session: SessionStore,
) : NearExpiryRepository {

    override suspend fun lotsFor(visitId: String): DataResult<List<NearExpiryLot>> = try {
        val body = service.load(visitId).orThrow()

        DataResult.Success(
            body.check?.lots.orEmpty().map {
                NearExpiryLot(
                    productId = it.productId,
                    productCode = it.productCode,
                    productName = it.productName,
                    lotNo = it.lotNo,
                    expiryDate = it.expiryDate,
                    uomCode = it.uomCode,
                    // The unit's display name is the catalogue's to give; the
                    // document only stores the code it was counted in.
                    uomName = it.uomCode,
                    qty = it.qty,
                    baseQty = it.baseQty,
                )
            },
        )
    } catch (e: Exception) {
        DataResult.Failure(e.toAppError())
    }

    override suspend fun submit(draft: DraftNearExpiry): DataResult<Unit> = try {
        // The rep owns the storage folder the policies authorise on, so without a
        // session there is nowhere legitimate to put the evidence.
        val salespersonId by lazy {
            session.current.value?.id
                ?: error("no signed-in salesperson to attribute the photos to")
        }

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

        // Where the shelf was standing, taken from the first shot. A document
        // with no photo has no coordinates either, which is the honest answer.
        val point = draft.photos.firstOrNull { it.lat != null }

        service.submit(
            NearExpiryPayload(
                // The idempotency key `submit_near_expiry_check` returns early on,
                // so a retry after a timeout that in fact succeeded does not
                // delete and rewrite the document.
                id = UUID.randomUUID().toString(),
                visitId = draft.visitId,
                checkDate = LocalDate.now().toString(),
                note = draft.note.trim().ifBlank { null },
                noPhotoReasonId = draft.noPhotoReasonId.takeIf { draft.photos.isEmpty() },
                lat = point?.lat,
                lng = point?.lng,
                clientCreatedAt = OffsetDateTime.now(ZoneOffset.UTC).toString(),
                lots = draft.lots.map { lot ->
                    NearExpiryLotPayload(
                        productId = lot.productId,
                        lotNo = lot.lotNo,
                        expiryDate = lot.expiryDate,
                        uomCode = lot.uomCode,
                        qty = lot.qty,
                        baseQty = lot.baseQty,
                    )
                },
                photos = uploaded,
            ),
        ).orThrow()

        // Only now, and from the draft rather than the payload: the payload
        // carries storage object names, not paths on this device.
        draft.photos.forEach { uploader.deleteLocal(it.localPath) }

        DataResult.Success(Unit)
    } catch (e: Exception) {
        DataResult.Failure(e.toAppError())
    }
}
