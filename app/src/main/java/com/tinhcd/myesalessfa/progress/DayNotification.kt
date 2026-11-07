package com.tinhcd.myesalessfa.progress

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.tinhcd.myesalessfa.MainActivity
import com.tinhcd.myesalessfa.R
import com.tinhcd.myesalessfa.core.ui.AppStrings
import com.tinhcd.myesalessfa.core.ui.compactMoney
import java.time.LocalDate

/** Planned stops drawn one segment each up to this many; past it, one bar. */
private const val MAX_SEGMENTS = 20

/**
 * The working day's notification, carrying the day's progress.
 *
 * It already had to be there -- the location trace runs as a foreground
 * service -- and it said only that the trace was running. Now it is worth
 * pulling the shade for: stops done of planned as a bar, orders and sales
 * beside it. On Android 16 it is a progress-centric notification, one
 * segment per planned stop, and asks to be promoted to a Live Update, so the
 * count sits in the status bar as a chip; on older phones it is a plain bar.
 *
 * Figures from another day are not shown: before the first refresh of the
 * morning, yesterday's count would read as today's.
 */
fun dayNotification(
    context: Context,
    channelId: String,
    strings: AppStrings,
    snapshot: TodaySnapshot?,
    today: LocalDate = LocalDate.now(),
): Notification {
    val day = snapshot?.takeIf { it.isFor(today) }

    val builder = NotificationCompat.Builder(context, channelId)
        .setSmallIcon(android.R.drawable.ic_menu_mylocation)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setContentIntent(
            PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )

    if (day == null || day.visitPlanned == 0 && day.visitDone == 0) {
        return builder
            .setContentTitle(strings(R.string.day_notif_title))
            .setContentText(strings(R.string.day_notif_tracking))
            .build()
    }

    val planned = maxOf(day.visitPlanned, day.visitDone)
    builder
        .setContentTitle(strings(R.string.day_notif_visits, day.visitDone, planned))
        .setContentText(dayLine(strings, day))
        .setSubText(strings(R.string.day_notif_tracking_short))
        .setRequestPromotedOngoing(true)
        .setShortCriticalText("${day.visitDone}/$planned")

    if (Build.VERSION.SDK_INT >= 36) {
        val style = NotificationCompat.ProgressStyle()
            .setStyledByProgress(true)
            .setProgress(day.visitDone)
        if (planned <= MAX_SEGMENTS) {
            repeat(planned) { style.addProgressSegment(NotificationCompat.ProgressStyle.Segment(1)) }
        } else {
            style.addProgressSegment(NotificationCompat.ProgressStyle.Segment(planned))
        }
        builder.setStyle(style)
    } else {
        builder.setProgress(planned, day.visitDone, false)
    }
    return builder.build()
}

/** "3 đơn · 4,2 tr · KPI tháng 68%", the month part only with a target. */
internal fun dayLine(strings: AppStrings, day: TodaySnapshot): String = buildList {
    add(strings(R.string.day_notif_orders, day.orderCount))
    add(compactMoney(day.revenue))
    day.monthPercent?.let { add(strings(R.string.day_notif_month, it)) }
}.joinToString(" · ")
