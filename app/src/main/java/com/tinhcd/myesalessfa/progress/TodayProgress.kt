package com.tinhcd.myesalessfa.progress

import android.content.Context
import androidx.glance.appwidget.updateAll
import com.tinhcd.myesalessfa.domain.DataResult
import com.tinhcd.myesalessfa.domain.repository.DashboardRepository
import com.tinhcd.myesalessfa.progress.widget.TodayWidget
import com.tinhcd.myesalessfa.tracking.TrackingService
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps the snapshot current and pushes it to where it is shown.
 *
 * Refreshed when the app goes to the background -- the moment after a
 * check-out or an order, when the rep pockets the phone -- and when the
 * working day starts. The widget also asks on its own schedule. Every path
 * comes through [fetch], so the figures come from one place, the same
 * overview the Tổng quan screen draws.
 */
@Singleton
class TodayProgress @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val dashboard: DashboardRepository,
) {
    val store = TodaySnapshotStore(context)

    /** The server's figures for today, saved; null when they could not be had. */
    suspend fun fetch(today: LocalDate = LocalDate.now()): TodaySnapshot? =
        when (val result = dashboard.overview(today)) {
            is DataResult.Success -> TodaySnapshot.from(result.data, System.currentTimeMillis()).also(store::write)
            is DataResult.Failure -> null
        }

    /** Fetches, then redraws the widget and the working-day notification. */
    suspend fun refresh() {
        fetch()
        publish()
    }

    /** Redraws from what is stored, without asking the server. */
    suspend fun publish() {
        TodayWidget().updateAll(context)
        TrackingService.refreshNotification(context)
    }

    suspend fun clear() {
        store.clear()
        publish()
    }
}
