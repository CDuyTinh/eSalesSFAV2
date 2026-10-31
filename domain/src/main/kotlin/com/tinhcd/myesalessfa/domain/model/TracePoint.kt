package com.tinhcd.myesalessfa.domain.model

/**
 * One fix taken by the tracking service during the working day. Kept as the
 * wire's own types: it is queued on the phone and sent as it was taken.
 */
data class TracePoint(
    /** ISO-8601 with offset, as the phone's clock had it. */
    val recordedAt: String,
    val lat: Double,
    val lng: Double,
    val accuracyM: Float?,
    val speedMps: Float?,
    val batteryPct: Int?,
)
