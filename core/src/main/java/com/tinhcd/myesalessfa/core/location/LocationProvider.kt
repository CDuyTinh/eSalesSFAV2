package com.tinhcd.myesalessfa.core.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import android.os.Looper
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.tinhcd.myesalessfa.domain.model.GeoPoint
import com.tinhcd.myesalessfa.domain.model.TracePoint
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import java.time.Instant
import java.time.ZoneOffset
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A single fresh fix, which is what check-in needs; and, separately, the
 * working-day stream the tracking service records ([updates]).
 *
 * Deliberately asks for a *current* location rather than the last known one:
 * a stale fix from the previous shop is exactly how you end up recording a
 * visit the rep never made.
 */
@Singleton
class LocationProvider @Inject constructor(
    // `@param:` keeps the qualifier on the constructor parameter, which is
    // where Dagger looks. Without it Kotlin will also start applying it to the
    // backing field in a future release.
    @param:ApplicationContext private val context: Context,
) {
    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    suspend fun currentLocation(): GeoPoint? {
        if (!hasPermission()) return null

        val client = LocationServices.getFusedLocationProviderClient(context)
        val request = CurrentLocationRequest.Builder()
            .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
            .setMaxUpdateAgeMillis(0)
            .build()

        val location = client.getCurrentLocation(request, null).await() ?: return null
        return GeoPoint(
            lat = location.latitude,
            lng = location.longitude,
            accuracyM = if (location.hasAccuracy()) location.accuracy else null,
        )
    }

    /**
     * A fix every [intervalMs], for the working-day trace. High accuracy: the
     * balanced setting answers indoors from cell towers, kilometres out, and the
     * server drops anything worse than gps_track_max_accuracy_m. At one fix
     * every few minutes the GPS is idle nearly all the time anyway.
     * Battery is left to the caller, which knows when it is asking.
     */
    @SuppressLint("MissingPermission")
    fun updates(intervalMs: Long): Flow<TracePoint> = callbackFlow {
        if (!hasPermission()) {
            close()
            return@callbackFlow
        }
        val client = LocationServices.getFusedLocationProviderClient(context)
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMs)
            .setMinUpdateIntervalMillis(intervalMs / 2)
            // Never a fix the phone had lying around from before the day began.
            .setMaxUpdateAgeMillis(0)
            .build()
        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.locations.forEach { l ->
                    trySend(
                        TracePoint(
                            recordedAt = Instant.ofEpochMilli(l.time).atOffset(ZoneOffset.ofHours(7)).toString(),
                            lat = l.latitude,
                            lng = l.longitude,
                            accuracyM = if (l.hasAccuracy()) l.accuracy else null,
                            speedMps = if (l.hasSpeed()) l.speed else null,
                            batteryPct = null,
                        ),
                    )
                }
            }
        }
        client.requestLocationUpdates(request, callback, Looper.getMainLooper())
        awaitClose { client.removeLocationUpdates(callback) }
    }
}
