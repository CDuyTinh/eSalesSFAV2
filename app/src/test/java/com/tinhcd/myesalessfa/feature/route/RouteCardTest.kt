package com.tinhcd.myesalessfa.feature.route

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.core.view.drawToBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tinhcd.myesalessfa.core.ui.theme.MyeSalesTheme
import com.tinhcd.myesalessfa.domain.model.Customer
import com.tinhcd.myesalessfa.domain.model.RouteStop
import com.tinhcd.myesalessfa.domain.model.VisitStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The ring round the stop being visited, drawn for real: it brightens and fades
 * over time, and a card that is not being visited has none. The frames are
 * saved under build/outputs/ring-frames so the ring can be looked at, not only
 * measured.
 *
 * Drawn straight from the window rather than with captureToImage, which waits
 * for a new frame and so never returns while the test holds the clock still.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class, qualifiers = "vi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RouteCardTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun stop(id: String, status: VisitStatus) = RouteStop(
        customer = Customer(
            id = id, code = "KH00$id", name = "Tạp hóa $id", address = "5 Hùng Vương",
            phone = "0281234006", lat = null, lng = null, avatarUrl = null, checkInRadiusM = 100,
        ),
        visitOrder = id.toInt(), status = status,
        visitId = null, checkInAtEpochMs = null, checkOutAtEpochMs = null,
    )

    private fun frame(): Bitmap = compose.activity.window.decorView.drawToBitmap()

    private fun bounds(tag: String): Rect = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInWindow

    /** Brightness at the middle of the card's top edge, where the ring runs. */
    private fun Bitmap.topEdge(card: Rect): Float {
        val p = getPixel(card.center.x.toInt(), card.top.toInt() + 1)
        return (((p shr 16) and 0xFF) + ((p shr 8) and 0xFF) + (p and 0xFF)) / 3f
    }

    private fun Bitmap.save(name: String) {
        val dir = File("build/outputs/ring-frames").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun `the card being visited breathes, and the others do not`() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MyeSalesTheme(darkTheme = true) {
                Column(Modifier.width(380.dp).padding(8.dp)) {
                    Box(Modifier.testTag("visiting")) {
                        StopCard(stop("7", VisitStatus.IN_PROGRESS), onOpen = {}, onOpenHub = {}, checkInBlocked = false, onCall = {})
                    }
                    Box(Modifier.padding(top = 12.dp).testTag("planned")) {
                        StopCard(stop("8", VisitStatus.PLANNED), onOpen = {}, onOpenHub = {}, checkInBlocked = true, onCall = {})
                    }
                }
            }
        }
        compose.mainClock.advanceTimeByFrame()
        val visiting = bounds("visiting")
        val planned = bounds("planned")

        val dim = frame()
        compose.mainClock.advanceTimeBy(1_200)
        val bright = frame()

        dim.save("ring_dim")
        bright.save("ring_bright")

        assertTrue(
            "the ring brightens over a breath (${dim.topEdge(visiting)} -> ${bright.topEdge(visiting)})",
            bright.topEdge(visiting) > dim.topEdge(visiting) + 20f,
        )
        assertEquals("a card not being visited has no ring", dim.topEdge(planned), bright.topEdge(planned), 0.5f)
    }
}
