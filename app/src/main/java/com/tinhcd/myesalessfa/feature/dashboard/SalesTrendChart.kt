package com.tinhcd.myesalessfa.feature.dashboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tinhcd.myesalessfa.domain.model.SalesPoint
import com.tinhcd.myesalessfa.domain.model.heights

/** Room under the plot for the day labels. */
private val LabelStripHeight = 20.dp

private const val GRID_LINES = 4

/**
 * The sell-out line for one span.
 *
 * Only the actual is drawn. The legacy screen built a target series alongside it
 * and then commented the line out — so a target line has never been on a rep's
 * screen, and there is nothing to reproduce.
 */
@Composable
fun SalesTrendChart(
    points: List<SalesPoint>,
    modifier: Modifier = Modifier,
) {
    if (points.isEmpty()) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text(
                text = "Chua co du lieu",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    val measurer = rememberTextMeasurer()
    val lineColor = MaterialTheme.colorScheme.primary
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val fill = Brush.verticalGradient(
        listOf(lineColor.copy(alpha = 0.28f), Color.Transparent),
    )

    Canvas(modifier) {
        val strip = LabelStripHeight.toPx()
        val plotHeight = (size.height - strip).coerceAtLeast(1f)
        val heights = points.heights()

        // A single point has no width to spread over; centring it keeps the
        // divisor away from zero and puts the one day where a reader expects it.
        val step = if (points.size > 1) size.width / (points.size - 1) else 0f
        val xOf = { index: Int -> if (points.size > 1) index * step else size.width / 2f }

        // Inset from both edges so the first and last dots are not sliced in half
        // by the canvas bounds.
        val dotRadius = 3.dp.toPx()
        val top = dotRadius
        val yOf = { fraction: Float -> top + (1f - fraction) * (plotHeight - top * 2) }

        repeat(GRID_LINES + 1) { line ->
            val y = top + (plotHeight - top * 2) * line / GRID_LINES
            drawLine(
                color = gridColor,
                start = Offset(0f, y),
                end = Offset(size.width, y),
                strokeWidth = 1f,
            )
        }

        val line = Path().apply {
            heights.forEachIndexed { index, fraction ->
                val point = Offset(xOf(index), yOf(fraction))
                if (index == 0) moveTo(point.x, point.y) else lineTo(point.x, point.y)
            }
        }

        // Closed back along the baseline for the shaded area. Kept separate from
        // the stroked path so the baseline itself is not drawn as part of the line.
        val area = Path().apply {
            addPath(line)
            lineTo(xOf(points.lastIndex), plotHeight)
            lineTo(xOf(0), plotHeight)
            close()
        }
        drawPath(area, brush = fill)
        drawPath(line, color = lineColor, style = Stroke(width = 2.dp.toPx()))

        heights.forEachIndexed { index, fraction ->
            drawCircle(lineColor, radius = dotRadius, center = Offset(xOf(index), yOf(fraction)))
        }

        drawLabels(points, measurer, labelColor, xOf, plotHeight)
    }
}

/**
 * A month is 31 days and 31 labels would overlap into a grey smear, so long spans
 * get every fifth day and the last one. Weeks are seven and always fit.
 */
private fun DrawScope.drawLabels(
    points: List<SalesPoint>,
    measurer: TextMeasurer,
    color: Color,
    xOf: (Int) -> Float,
    plotHeight: Float,
) {
    val stride = if (points.size > 8) 5 else 1
    val style = TextStyle(color = color, fontSize = 10.sp)

    points.forEachIndexed { index, point ->
        val show = index % stride == 0 || index == points.lastIndex
        if (!show) return@forEachIndexed

        val measured = measurer.measure(point.title, style)
        // Clamped so the first and last labels stay inside the canvas instead of
        // being centred on a point that sits on the edge.
        val x = (xOf(index) - measured.size.width / 2f)
            .coerceIn(0f, (size.width - measured.size.width).coerceAtLeast(0f))
        drawText(measured, topLeft = Offset(x, plotHeight + 4.dp.toPx()))
    }
}
