package com.tinhcd.myesalessfa.data.remote.service

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST

@Serializable
data class TracePointDto(
    @SerialName("recorded_at") val recordedAt: String,
    val lat: Double,
    val lng: Double,
    @SerialName("accuracy_m") val accuracyM: Float? = null,
    @SerialName("speed_mps") val speedMps: Float? = null,
    @SerialName("battery_pct") val batteryPct: Int? = null,
)

@Serializable
data class TraceBatchDto(val points: List<TracePointDto>)

@Serializable
data class TraceAckDto(val accepted: Int = 0, val dropped: Int = 0)

/** `submit-location`: the tracking service's batches. */
interface LocationTraceService {
    @POST("submit-location")
    suspend fun submit(@Body batch: TraceBatchDto): Response<TraceAckDto>
}
