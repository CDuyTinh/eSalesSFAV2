package com.tinhcd.myesalessfa.tracking

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.tinhcd.myesalessfa.MainActivity
import com.tinhcd.myesalessfa.core.location.LocationProvider
import com.tinhcd.myesalessfa.domain.repository.LocationTraceRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var collecting: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopTracking()
            return START_NOT_STICKY
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
        if (collecting == null) {
            collecting = scope.launch {
                val intervalMs = trace.intervalSeconds() * 1000L
                trace.flush()
                location.updates(intervalMs).collect { fix ->
                    trace.record(fix.copy(batteryPct = batteryPct()))
                    trace.flush()
                }
            }
        }
        return START_STICKY
    }

    private fun stopTracking() {
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
        scope.cancel()
        super.onDestroy()
    }

    private fun batteryPct(): Int? =
        (getSystemService(Context.BATTERY_SERVICE) as? BatteryManager)
            ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            ?.takeIf { it in 0..100 }

    private fun notification() = NotificationCompat.Builder(this, ensureChannel())
        .setSmallIcon(android.R.drawable.ic_menu_mylocation)
        .setContentTitle("Đang trong ngày làm việc")
        .setContentText("Vị trí được ghi lại đến khi bạn chấm công ra")
        .setOngoing(true)
        .setContentIntent(
            PendingIntent.getActivity(
                this, 0, Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
        .build()

    private fun ensureChannel(): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Theo dõi vị trí", NotificationManager.IMPORTANCE_LOW).apply {
                        description = "Thông báo khi vị trí đang được ghi lại trong ngày làm việc"
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

        /** Safe to call repeatedly: a running service keeps its one collection. */
        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, TrackingService::class.java))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, TrackingService::class.java).setAction(ACTION_STOP))
        }
    }
}
