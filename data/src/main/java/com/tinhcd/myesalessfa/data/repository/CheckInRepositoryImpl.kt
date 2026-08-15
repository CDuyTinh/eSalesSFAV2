package com.tinhcd.myesalessfa.data.repository

import com.tinhcd.myesalessfa.data.remote.dto.NewVisitDto
import com.tinhcd.myesalessfa.data.remote.api.VisitApi
import com.tinhcd.myesalessfa.data.session.SessionStore
import com.tinhcd.myesalessfa.domain.AppError
import com.tinhcd.myesalessfa.domain.DataResult
import com.tinhcd.myesalessfa.domain.model.CheckInRequest
import com.tinhcd.myesalessfa.domain.repository.CheckInRepository
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
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
    private val visitApi: VisitApi,
    private val session: SessionStore,
) : CheckInRepository {

    override suspend fun checkIn(request: CheckInRequest): DataResult<Unit> {
        val me = session.current.value
            ?: return DataResult.Failure(AppError.Auth("not_signed_in"))

        return try {
            visitApi.insertVisit(
                NewVisitDto(
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
                    checkInPhotoPath = request.photoPath,
                    note = request.note,
                ),
            )
            DataResult.Success(Unit)
        } catch (e: Exception) {
            DataResult.Failure(e.toAppError())
        }
    }

    override suspend fun checkOut(visitId: String): DataResult<Unit> = try {
        visitApi.markCheckedOut(
            visitId = visitId,
            checkOutAt = OffsetDateTime.now(ZoneOffset.UTC).toString(),
        )
        DataResult.Success(Unit)
    } catch (e: Exception) {
        DataResult.Failure(e.toAppError())
    }
}
