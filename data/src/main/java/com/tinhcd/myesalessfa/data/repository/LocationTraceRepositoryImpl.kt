package com.tinhcd.myesalessfa.data.repository

import com.tinhcd.myesalessfa.data.local.ConfigDao
import com.tinhcd.myesalessfa.data.local.TraceQueue
import com.tinhcd.myesalessfa.data.remote.http.orThrow
import com.tinhcd.myesalessfa.data.remote.service.LocationTraceService
import com.tinhcd.myesalessfa.data.remote.service.TraceBatchDto
import com.tinhcd.myesalessfa.data.remote.service.TracePointDto
import com.tinhcd.myesalessfa.domain.DataResult
import com.tinhcd.myesalessfa.domain.model.TracePoint
import com.tinhcd.myesalessfa.domain.repository.LocationTraceRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LocationTraceRepositoryImpl @Inject constructor(
    private val service: LocationTraceService,
    private val queue: TraceQueue,
    private val config: ConfigDao,
) : LocationTraceRepository {

    override suspend fun intervalSeconds(): Int =
        config.setting("gps_track_interval_s")?.toIntOrNull()?.coerceIn(30, 3600) ?: DEFAULT_INTERVAL_S

    override suspend fun record(point: TracePoint) = queue.append(
        TracePointDto(point.recordedAt, point.lat, point.lng, point.accuracyM, point.speedMps, point.batteryPct),
    )

    override suspend fun flush(): DataResult<Int> = try {
        while (true) {
            val batch = queue.peek(BATCH)
            if (batch.isEmpty()) break
            service.submit(TraceBatchDto(batch)).orThrow()
            queue.drop(batch.size)
            if (batch.size < BATCH) break
        }
        DataResult.Success(queue.size())
    } catch (e: Exception) {
        // Left queued for the next tick: the network is the usual cause.
        DataResult.Failure(e.toAppError())
    }

    private companion object {
        const val DEFAULT_INTERVAL_S = 300
        const val BATCH = 200
    }
}
