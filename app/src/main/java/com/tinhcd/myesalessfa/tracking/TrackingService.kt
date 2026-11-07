package com.tinhcd.myesalessfa.tracking

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.tinhcd.myesalessfa.R
import com.tinhcd.myesalessfa.core.ui.AppStrings
import com.tinhcd.myesalessfa.progress.TodayProgress
import com.tinhcd.myesalessfa.progress.dayNotification
import com.tinhcd.myesalessfa.core.location.LocationProvider
import com.tinhcd.myesalessfa.domain.model.TracePoint
import com.tinhcd.myesalessfa.domain.repository.LocationTraceRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.OffsetDateTime
import javax.inject.Inject

/**
 * Records where the rep is while the working day is open (legacy
 * ServiceBackground), for the manager's map.
 *
 * A foreground service with its own notification, so the rep can always see
 * that they are being located and why. It runs from clock-in to clock-out: the
 * shell starts it when the day is open and stops it when it is not, and the
 * server keeps nothing recorded outside that window anyway.
 *
 * Fixes go to a queue on the phone first and are sent after each one, so a
 * dead zone delays them rather than losing them.
 */
@AndroidEntryPoint
class TrackingService : Service() {

    @Inject lateinit var location: LocationProvider
    @Inject lateinit var trace: LocationTraceRepository
    @Inject lateinit var strings: AppStrings
    @Inject lateinit var progress: TodayProgress

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var collecting: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopTracking()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_REFRESH) {
            // New figures for the bar; only reached while running (see refreshNotification).
            if (running) getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification())
            return START_STICKY
        }
        if (!location.hasPermission()) {
            // Without location there is nothing to record; the shell asks again.
            stopSelf()
            return START_NOT_STICKY
        }
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, notification(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0,
        )
        running = true
        if (collecting == null) {
            // The day's figures for the notification, which starts from the stored ones.
            scope.launch { progress.refresh() }
            collecting = scope.launch {
                val intervalMs = trace.intervalSeconds() * 1000L
                trace.flush()
                // A fresh fix now, so the trace starts at clock-in rather than one
                // interval later.
                location.currentLocation()?.let { here ->
                    trace.record(
                        TracePoint(
                            recordedAt = OffsetDateTime.now().toString(),
                            lat = here.lat, lng = here.lng, accuracyM = here.accuracyM,
                            speedMps = null, batteryPct = batteryPct(),
                        ),
                    )
                    trace.flush()
                }
                location.updates(intervalMs).collect { fix ->
                    trace.record(fix.copy(batteryPct = batteryPct()))
                    trace.flush()
                }
            }
        }
        return START_STICKY
    }

    private fun stopTracking() {
        running = false
        collecting?.cancel()
        collecting = null
        // One last try at what is still queued; the next day's start sends the rest.
        scope.launch {
            trace.flush()
            ServiceCompat.stopForeground(this@TrackingService, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onDestroy() {
        running = false
        scope.cancel()
        super.onDestroy()
    }

    private fun batteryPct(): Int? =
        (getSystemService(Context.BATTERY_SERVICE) as? BatteryManager)
            ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            ?.takeIf { it in 0..100 }

    private fun notification() = dayNotification(this, ensureChannel(), strings, progress.store.read())

    private fun ensureChannel(): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, strings(R.string.day_notif_channel), NotificationManager.IMPORTANCE_LOW).apply {
                        description = strings(R.string.day_notif_channel_desc)
                    },
                )
            }
        }
        return CHANNEL_ID
    }

    companion object {
        private const val CHANNEL_ID = "tracking"
        private const val NOTIFICATION_ID = 4101
        private const val ACTION_STOP = "com.tinhcd.myesalessfa.tracking.STOP"
        private const val ACTION_REFRESH = "com.tinhcd.myesalessfa.tracking.REFRESH"

        /** Whether the day is being traced, so a refresh knows there is a notification to redraw. */
        @Volatile private var running = false

        /**
         * Safe to call repeatedly: a running service keeps its one collection.
         *
         * Does nothing without location permission. A service started with
         * startForegroundService must reach startForeground within seconds or the
         * system kills the app, and a location-type service cannot without the
         * permission — so starting one anyway crashed the app on open (seen on a
         * phone whose app data had been cleared, with the day still open).
         */
        fun start(context: Context) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
            ContextCompat.startForegroundService(context, Intent(context, TrackingService::class.java))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, TrackingService::class.java).setAction(ACTION_STOP))
        }

        /**
         * Redraws the notification from the stored figures. Nothing when the day
         * is not running: starting the service just to show progress would
         * start the location trace with it.
         */
        fun refreshNotification(context: Context) {
            if (!running) return
            context.startService(Intent(context, TrackingService::class.java).setAction(ACTION_REFRESH))
        }
    }
}
