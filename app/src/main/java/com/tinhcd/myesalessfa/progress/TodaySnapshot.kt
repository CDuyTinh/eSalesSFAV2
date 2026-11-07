package com.tinhcd.myesalessfa.progress

import android.content.Context
import androidx.core.content.edit
import com.tinhcd.myesalessfa.domain.model.DashboardOverview
import java.time.LocalDate

/**
 * The day so far, in the few numbers a rep glances at between shops: stops
 * done of planned, orders, sales, and the month against target.
 *
 * Shown where the app is not open -- the home-screen widget and the working
 * day's notification -- so it is kept on the phone and drawn from there,
 * rather than each of them asking the server when the system wakes it.
 */
data class TodaySnapshot(
    val date: LocalDate,
    val visitDone: Int,
    val visitPlanned: Int,
    val orderCount: Int,
    val revenue: Long,
    /** Month revenue against target, as written (may exceed 100); null with no target. */
    val monthPercent: Int?,
    val updatedAtMs: Long,
) {
    /** 0..100 of the planned stops, for a bar; 0 on a day with nothing planned. */
    val visitPercent: Int
        get() = if (visitPlanned <= 0) 0 else (visitDone * 100 / visitPlanned).coerceIn(0, 100)

    fun isFor(day: LocalDate): Boolean = date == day

    companion object {
        fun from(overview: DashboardOverview, nowMs: Long) = TodaySnapshot(
            date = overview.date,
            visitDone = overview.today.visitDone,
            visitPlanned = overview.today.visitPlanned,
            orderCount = overview.today.orderCount,
            revenue = overview.today.revenue,
            monthPercent = overview.month.revenuePercent,
            updatedAtMs = nowMs,
        )
    }
}

/** The last snapshot, in the app's own preferences. */
class TodaySnapshotStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("today_progress", Context.MODE_PRIVATE)

    fun read(): TodaySnapshot? {
        val date = prefs.getString(DATE, null)?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return null
        return TodaySnapshot(
            date = date,
            visitDone = prefs.getInt(VISIT_DONE, 0),
            visitPlanned = prefs.getInt(VISIT_PLANNED, 0),
            orderCount = prefs.getInt(ORDERS, 0),
            revenue = prefs.getLong(REVENUE, 0),
            monthPercent = prefs.getInt(MONTH_PCT, -1).takeIf { it >= 0 },
            updatedAtMs = prefs.getLong(UPDATED, 0),
        )
    }

    fun write(snapshot: TodaySnapshot) = prefs.edit {
        putString(DATE, snapshot.date.toString())
        putInt(VISIT_DONE, snapshot.visitDone)
        putInt(VISIT_PLANNED, snapshot.visitPlanned)
        putInt(ORDERS, snapshot.orderCount)
        putLong(REVENUE, snapshot.revenue)
        putInt(MONTH_PCT, snapshot.monthPercent ?: -1)
        putLong(UPDATED, snapshot.updatedAtMs)
    }

    /** Signed out: another rep's figures must not sit on the home screen. */
    fun clear() = prefs.edit { clear() }

    private companion object {
        const val DATE = "date"
        const val VISIT_DONE = "visit_done"
        const val VISIT_PLANNED = "visit_planned"
        const val ORDERS = "orders"
        const val REVENUE = "revenue"
        const val MONTH_PCT = "month_pct"
        const val UPDATED = "updated"
    }
}
