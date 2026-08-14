package com.tinhcd.myesalessfa.data.repository

import android.content.Context
import com.tinhcd.myesalessfa.data.local.OutboxDao
import com.tinhcd.myesalessfa.data.local.OutboxEntity
import com.tinhcd.myesalessfa.data.outbox.CheckOutPayload
import com.tinhcd.myesalessfa.data.outbox.OutboxFlusher
import com.tinhcd.myesalessfa.data.outbox.OutboxWorker
import com.tinhcd.myesalessfa.data.remote.NewVisitDto
import com.tinhcd.myesalessfa.data.session.SessionStore
import com.tinhcd.myesalessfa.domain.AppError
import com.tinhcd.myesalessfa.domain.DataResult
import com.tinhcd.myesalessfa.domain.model.CheckInRequest
import com.tinhcd.myesalessfa.domain.repository.CheckInRepository
import com.tinhcd.myesalessfa.domain.repository.SubmitOutcome
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import javax.inject.Inject
import javax.inject.Singleton

private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/**
 * Local-first for exactly these two operations.
 *
 * Everything else in this app is a plain online call — if a product list fails
 * to load the rep taps retry and nothing is lost. A check-in is different: the
 * rep is physically at the shop now, and that fact cannot be reconstructed
 * later. So it is written to the outbox first and only then sent.
 */
@Singleton
class CheckInRepositoryImpl @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val outboxDao: OutboxDao,
    private val flusher: OutboxFlusher,
    private val session: SessionStore,
) : CheckInRepository {

    override val pendingCount: Flow<Int> = outboxDao.pendingCount()

    override suspend fun checkIn(request: CheckInRequest): DataResult<SubmitOutcome> {
        val me = session.current.value
            ?: return DataResult.Failure(AppError.Auth("not_signed_in"))

        val payload = NewVisitDto(
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
        )

        return enqueueThenTry(OutboxEntity.TYPE_CHECK_IN, json.encodeToString(payload))
    }

    override suspend fun checkOut(visitId: String): DataResult<SubmitOutcome> {
        val payload = CheckOutPayload(
            visitId = visitId,
            checkOutAt = OffsetDateTime.now(ZoneOffset.UTC).toString(),
        )
        return enqueueThenTry(OutboxEntity.TYPE_CHECK_OUT, json.encodeToString(payload))
    }

    private suspend fun enqueueThenTry(type: String, payload: String): DataResult<SubmitOutcome> =
        try {
            outboxDao.insert(
                OutboxEntity(
                    type = type,
                    payload = payload,
                    createdAt = System.currentTimeMillis(),
                ),
            )

            // Try straight away so a rep on good signal sees it land. If this
            // fails the entry stays queued and WorkManager takes over.
            val drained = runCatching { flusher.flush() }.getOrDefault(false)
            if (drained) {
                DataResult.Success(SubmitOutcome.SENT)
            } else {
                OutboxWorker.enqueue(context)
                DataResult.Success(SubmitOutcome.QUEUED)
            }
        } catch (e: Exception) {
            // Could not even reach local storage — nothing left to fall back on.
            DataResult.Failure(e.toAppError())
        }
}
