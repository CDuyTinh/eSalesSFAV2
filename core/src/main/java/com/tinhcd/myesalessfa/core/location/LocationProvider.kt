package com.tinhcd.myesalessfa.core.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.tinhcd.myesalessfa.domain.model.GeoPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A single fresh fix, which is what check-in needs — not a stream.
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
}
