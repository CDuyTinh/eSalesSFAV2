package com.tinhcd.myesalessfa.push

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.tinhcd.myesalessfa.MainActivity
import com.tinhcd.myesalessfa.R

/**
 * The channel pushes arrive on, and showing one while the app is open.
 *
 * In the background FCM shows the notification itself, on the channel the
 * server names ("general"), which is why the channel is created at app start
 * rather than on first use. In the foreground it hands the message to
 * [SfaMessagingService] instead, which shows it here.
 */
object PushNotifications {
    const val CHANNEL_ID = "general"

    /** Which screen a tap should open, in the intent that opens the app. */
    const val EXTRA_SCREEN = "screen"

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Thông báo", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Duyệt đơn, kế hoạch và chỉ tiêu từ quản lý"
                },
            )
        }
    }

    fun show(context: Context, title: String?, body: String?, screen: String?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return

        val open = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(EXTRA_SCREEN, screen)
        val tap = PendingIntent.getActivity(
            context, System.currentTimeMillis().toInt(), open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(tap)
            .build()

        NotificationManagerCompat.from(context).notify(System.currentTimeMillis().toInt(), notification)
    }
}
