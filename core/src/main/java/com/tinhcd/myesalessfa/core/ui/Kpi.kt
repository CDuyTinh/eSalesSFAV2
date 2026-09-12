package com.tinhcd.myesalessfa.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * The figures language, shared by the Overview tab and the reports.
 *
 * These began as private helpers on the dashboard. The report tabs were drawn
 * with plain grey text instead, and the difference showed: two screens answering
 * the same kind of question — how am I doing — that looked like two apps. They
 * live here now so there is one answer to what a headline number looks like.
 */
object Kpi {
    /**
     * Fixed rather than theme-derived, and light text on all of them.
     *
     * A row of tiles reads as one row, so they have to stay distinguishable from
     * each other rather than from the background — which is what would happen if
     * they tracked the colour scheme and dark mode pulled them together.
     */
    val Blue = listOf(Color(0xFF2D2DFE), Color(0xFF5757FE))
    val Green = listOf(Color(0xFF04A489), Color(0xFF36B6A0))
    val Orange = listOf(Color(0xFFFF5A30), Color(0xFFFF7B59))
    val Violet = listOf(Color(0xFF5C00D4), Color(0xFF8B3FE8))
    val Slate = listOf(Color(0xFF4A5568), Color(0xFF718096))
}

/** One headline figure on a coloured ground. */
@Composable
fun StatTile(
    label: String,
    value: String,
    colors: List<Color>,
    modifier: Modifier = Modifier,
    footer: String? = null,
) {
    Column(
        modifier = modifier
            .background(Brush.verticalGradient(colors), RoundedCornerShape(12.dp))
            .padding(vertical = 12.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.9f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (footer != null) {
            Text(
                text = footer,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.85f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** A share of something, drawn on the same ramp as the figure it belongs to. */
@Composable
fun KpiBar(progress: Float, ramp: List<Color>, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(10.dp)
            .clip(CircleShape)
            .background(ramp.first().copy(alpha = 0.15f)),
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .clip(CircleShape)
                .background(Brush.horizontalGradient(ramp)),
        )
    }
}

@Composable
fun PercentPill(percent: Int, color: Color, modifier: Modifier = Modifier) {
    Surface(
        color = color.copy(alpha = 0.14f),
        contentColor = color,
        shape = CircleShape,
        modifier = modifier,
    ) {
        Text(
            text = "$percent%",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

/**
 * A short status word on its own colour.
 *
 * Same shape wherever a row carries a state — an order, a plan, a registration —
 * so a rep learns to read the corner of a card once.
 */
@Composable
fun StatusPill(text: String, color: Color, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = color.copy(alpha = 0.16f),
        contentColor = color,
        modifier = modifier,
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
        )
    }
}

/**
 * A big number in as few characters as a tile can hold.
 *
 * 3.578.520 does not fit a third of a phone's width, and truncating it would
 * leave "3.578…" — which reads as a different figure rather than a shortened
 * one. Rounded to two significant places instead, with the unit said out loud.
 */
fun compactMoney(value: Long): String = when {
    value >= 1_000_000_000 -> trimZero(value / 1_000_000_000.0) + " tỷ"
    value >= 1_000_000 -> trimZero(value / 1_000_000.0) + " tr"
    value >= 1_000 -> trimZero(value / 1_000.0) + " ng"
    else -> value.toString()
}

private fun trimZero(value: Double): String {
    val rounded = Math.round(value * 10) / 10.0
    return if (rounded == Math.floor(rounded)) rounded.toLong().toString()
    else rounded.toString().replace('.', ',')
}
