package com.tinhcd.myesalessfa.progress.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.tinhcd.myesalessfa.MainActivity
import com.tinhcd.myesalessfa.R
import com.tinhcd.myesalessfa.core.ui.AppStrings
import com.tinhcd.myesalessfa.core.ui.compactMoney
import com.tinhcd.myesalessfa.progress.TodayProgress
import com.tinhcd.myesalessfa.progress.TodaySnapshot
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Figures older than this are fetched again when the widget is drawn. */
private const val STALE_MS = 10 * 60_000L

@EntryPoint
@InstallIn(SingletonComponent::class)
interface TodayWidgetEntryPoint {
    fun todayProgress(): TodayProgress
    fun strings(): AppStrings
}

private fun Context.entry() = EntryPointAccessors.fromApplication(this, TodayWidgetEntryPoint::class.java)

/**
 * Hôm nay on the home screen: stops done of planned with a bar, orders, sales
 * and the month against target. Tapping it opens the app; the small refresh
 * fetches now.
 *
 * Drawn from the stored snapshot, fetched first when that is from another
 * day or more than a few minutes old. Text is read through [AppStrings] up
 * front, because a widget is drawn by the launcher in the phone's language
 * and the rep may have chosen another.
 */
class TodayWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val entry = context.entry()
        val progress = entry.todayProgress()
        val today = LocalDate.now()

        var snapshot = progress.store.read()
        if (snapshot == null || !snapshot.isFor(today) || System.currentTimeMillis() - snapshot.updatedAtMs > STALE_MS) {
            snapshot = progress.fetch(today) ?: snapshot
        }
        val labels = WidgetLabels.read(entry.strings(), snapshot?.takeIf { it.isFor(today) })

        provideContent {
            GlanceTheme { TodayContent(labels) }
        }
    }
}

class TodayWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TodayWidget()
}

/** The refresh tap: asks the server, then redraws every placed copy. */
class RefreshTodayAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        context.entry().todayProgress().refresh()
    }
}

/** Every string the widget shows, resolved in the rep's language. */
internal data class WidgetLabels(
    val title: String,
    val updated: String?,
    val visits: String?,
    val visitFraction: Float,
    val orders: String,
    val revenue: String,
    val month: String?,
    val empty: String?,
    val refresh: String,
) {
    companion object {
        private val clock = DateTimeFormatter.ofPattern("HH:mm")

        fun read(strings: AppStrings, day: TodaySnapshot?): WidgetLabels = WidgetLabels(
            title = strings(R.string.dashboard_today),
            updated = day?.let {
                strings(
                    R.string.widget_updated,
                    clock.format(Instant.ofEpochMilli(it.updatedAtMs).atZone(ZoneId.systemDefault())),
                )
            },
            visits = day?.let { strings(R.string.widget_visits, it.visitDone, maxOf(it.visitPlanned, it.visitDone)) },
            visitFraction = (day?.visitPercent ?: 0) / 100f,
            orders = strings(R.string.dashboard_order_count, day?.orderCount ?: 0),
            revenue = compactMoney(day?.revenue ?: 0),
            month = day?.monthPercent?.let { strings(R.string.day_notif_month, it) },
            empty = if (day == null) strings(R.string.widget_empty) else null,
            refresh = strings(R.string.widget_refresh),
        )
    }
}

@Composable
private fun TodayContent(labels: WidgetLabels) {
    val onSurface = GlanceTheme.colors.onSurface
    val muted = GlanceTheme.colors.onSurfaceVariant

    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(GlanceTheme.colors.widgetBackground)
            .cornerRadius(16.dp)
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .clickable(actionStartActivity<MainActivity>()),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = GlanceModifier.fillMaxWidth()) {
            Text(
                labels.title,
                style = TextStyle(color = onSurface, fontSize = 15.sp, fontWeight = FontWeight.Bold),
                modifier = GlanceModifier.defaultWeight(),
            )
            labels.updated?.let {
                Text(it, style = TextStyle(color = muted, fontSize = 11.sp))
            }
            Text(
                "  ⟳",
                style = TextStyle(color = GlanceTheme.colors.primary, fontSize = 16.sp, fontWeight = FontWeight.Bold),
                modifier = GlanceModifier
                    .clickable(actionRunCallback<RefreshTodayAction>())
                    .semantics { contentDescription = labels.refresh },
            )
        }

        Spacer(GlanceModifier.height(8.dp))

        if (labels.empty != null) {
            Text(labels.empty, style = TextStyle(color = muted, fontSize = 13.sp))
        } else {
            labels.visits?.let {
                Text(it, style = TextStyle(color = onSurface, fontSize = 13.sp, fontWeight = FontWeight.Medium))
            }
            Spacer(GlanceModifier.height(4.dp))
            LinearProgressIndicator(
                progress = labels.visitFraction,
                color = GlanceTheme.colors.primary,
                backgroundColor = GlanceTheme.colors.surfaceVariant,
                modifier = GlanceModifier.fillMaxWidth().height(6.dp),
            )
            Spacer(GlanceModifier.height(10.dp))

            Row(modifier = GlanceModifier.fillMaxWidth()) {
                Figure(labels.orders, GlanceModifier.defaultWeight())
                Figure(labels.revenue, GlanceModifier.defaultWeight())
                labels.month?.let { Figure(it, GlanceModifier.defaultWeight()) }
            }
        }
    }
}

@Composable
private fun Figure(text: String, modifier: GlanceModifier) {
    Text(
        text,
        style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Bold),
        modifier = modifier,
    )
}
