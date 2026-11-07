package com.tinhcd.myesalessfa.progress

import android.app.Application
import android.app.Notification
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tinhcd.myesalessfa.core.ui.AppStrings
import com.tinhcd.myesalessfa.progress.widget.WidgetLabels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.LocalDate

/**
 * The day's figures away from the app: kept on the phone, never shown on the
 * wrong day, and drawn into the widget and the working-day notification.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class, qualifiers = "vi")
class TodayProgressTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val strings = AppStrings(context)
    private val today = LocalDate.of(2026, 11, 7)

    private val day = TodaySnapshot(
        date = today, visitDone = 5, visitPlanned = 12, orderCount = 3,
        revenue = 4_200_000, monthPercent = 68, updatedAtMs = 1_000,
    )

    @Test
    fun `the snapshot survives on the phone and goes on sign-out`() {
        val store = TodaySnapshotStore(context)

        store.write(day)
        assertEquals(day, store.read())

        store.write(day.copy(monthPercent = null))
        assertNull("no target stays no target", store.read()!!.monthPercent)

        store.clear()
        assertNull(store.read())
    }

    @Test
    fun `the bar is a share of the planned stops, and never more than full`() {
        assertEquals(41, day.visitPercent)
        assertEquals(100, day.copy(visitDone = 14).visitPercent)
        assertEquals(0, day.copy(visitDone = 0, visitPlanned = 0).visitPercent)
    }

    @Test
    fun `the notification shows today's progress as a bar`() {
        val n = dayNotification(context, "test", strings, day, today)

        assertEquals("Đã ghé 5/12 cửa hàng", n.extras.getString(Notification.EXTRA_TITLE))
        assertEquals("3 đơn · 4,2 tr · KPI tháng 68%", n.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertEquals(12, n.extras.getInt(Notification.EXTRA_PROGRESS_MAX))
        assertEquals(5, n.extras.getInt(Notification.EXTRA_PROGRESS))
        assertTrue(n.flags and Notification.FLAG_ONGOING_EVENT != 0)
    }

    @Test
    fun `yesterday's figures are not passed off as today's`() {
        val n = dayNotification(context, "test", strings, day, today.plusDays(1))

        assertEquals("Đang trong ngày làm việc", n.extras.getString(Notification.EXTRA_TITLE))
        assertEquals(0, n.extras.getInt(Notification.EXTRA_PROGRESS_MAX))

        val labels = WidgetLabels.read(strings, null)
        assertNotNull(labels.empty)
        assertNull(labels.visits)
    }

    @Test
    fun `the widget reads the same figures`() {
        val labels = WidgetLabels.read(strings, day)

        assertNull(labels.empty)
        assertEquals("Viếng thăm 5/12", labels.visits)
        assertEquals(0.41f, labels.visitFraction, 0.001f)
        assertEquals("3 đơn", labels.orders)
        assertEquals("4,2 tr", labels.revenue)
        assertEquals("KPI tháng 68%", labels.month)
    }
}
