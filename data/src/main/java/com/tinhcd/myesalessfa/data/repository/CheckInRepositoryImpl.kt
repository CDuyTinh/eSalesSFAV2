package com.tinhcd.myesalessfa.data.repository

import com.tinhcd.myesalessfa.data.remote.dto.NewVisitDto
import com.tinhcd.myesalessfa.data.remote.http.orThrow
import com.tinhcd.myesalessfa.data.remote.service.VisitService
import com.tinhcd.myesalessfa.data.remote.storage.PhotoUploader
import com.tinhcd.myesalessfa.data.session.SessionStore
import com.tinhcd.myesalessfa.domain.AppError
import com.tinhcd.myesalessfa.domain.DataResult
import com.tinhcd.myesalessfa.domain.model.CheckInRequest
import com.tinhcd.myesalessfa.domain.repository.CheckInRepository
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Straight to the server, like every other write in the app.
 *
 * A failed check-in is reported and the rep tries again while they are still at the
 * shop. Nothing is held on the device: the app is online-only, so there is no queue
 * to drain and no second copy of the truth to reconcile.
 */
@Singleton
class CheckInRepositoryImpl @Inject constructor(
    private val service: VisitService,
    private val session: SessionStore,
    private val uploader: PhotoUploader,
) : CheckInRepository {

    override suspend fun checkIn(request: CheckInRequest): DataResult<Unit> {
        val me = session.current.value
            ?: return DataResult.Failure(AppError.Auth("not_signed_in"))

        return try {
            // The visit's id is chosen here so the shop-front photo can go up
            // first, under the path storage authorises (<rep>/<visit>/<file>);
            // a check-in whose photo did not land is not sent at all.
            val visitId = UUID.randomUUID().toString()
            val photoPath = request.photoLocalPath?.let { uploader.upload(me.id, visitId, it) }

            service.submitVisit(
                NewVisitDto(
                    id = visitId,
                    customerId = request.customerId,
                    salespersonId = me.id,
                    branchId = me.branchId,
                    visitDate = LocalDate.now().toString(),
                    status = "in_progress",
                    checkInAt = OffsetDateTime.now(ZoneOffset.UTC).toString(),
                    checkInLat = request.point.lat,
                    checkInLng = request.point.lng,
                    checkInAccuracyM = request.point.accuracyM?.toDouble(),
                    checkInDistanceM = request.distanceM,
                    checkInPhotoPath = photoPath,
                    // Was never sent: the screen asked for a reason and the row
                    // arrived without one.
                    checkInReasonId = request.reasonId,
                    note = request.note,
                ),
            ).orThrow()
            request.photoLocalPath?.let(uploader::deleteLocal)
            DataResult.Success(Unit)
        } catch (e: Exception) {
            DataResult.Failure(e.toAppError())
        }
    }

    /**
     * A few fields, so the body is built here rather than given a type of its own.
     * The photo goes up first, into the visit's own folder, for the reason the
     * check-in gives: a check-out whose photo did not land is not sent.
     */
    override suspend fun checkOut(visitId: String, photoLocalPath: String?): DataResult<Unit> = try {
        val me = session.current.value ?: throw IllegalStateException("not_signed_in")
        val photoPath = photoLocalPath?.let { uploader.upload(me.id, visitId, it) }
        service.submitCheckout(
            buildJsonObject {
                put("visit_id", visitId)
                put("check_out_at", OffsetDateTime.now(ZoneOffset.UTC).toString())
                photoPath?.let { put("check_out_photo_path", it) }
            },
        ).orThrow()
        photoLocalPath?.let(uploader::deleteLocal)
        DataResult.Success(Unit)
    } catch (e: Exception) {
        DataResult.Failure(e.toAppError())
    }
}
