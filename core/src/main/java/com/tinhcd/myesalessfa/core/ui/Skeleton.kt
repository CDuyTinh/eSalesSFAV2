package com.tinhcd.myesalessfa.core.ui

import androidx.compose.animation.core.LinearEasing
import com.tinhcd.myesalessfa.core.R
import androidx.compose.ui.res.stringResource
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/*
 * Skeleton screens: grey blocks in the shape of what is coming, with a light
 * sweeping across them, in place of a spinner. The page keeps its layout while
 * it loads, so nothing jumps when the figures arrive, and the rep can see
 * what kind of screen it will be. Every page that loads before it can show
 * anything uses one: lists of cards, details cards, forms, the map. Spinners
 * are left for work in progress inside a page — a button waiting on the
 * server, a GPS fix being taken.
 */

/** The shimmer: a soft band moving left to right across the placeholder. */
fun Modifier.shimmer(): Modifier = composed {
    // Tints of the text colour rather than surface colours, so the band reads as
    // a highlight on a light card and on a dark one alike.
    val ink = MaterialTheme.colorScheme.onSurface
    val base = ink.copy(alpha = 0.08f)
    val shine = ink.copy(alpha = 0.18f)
    val sweep by rememberInfiniteTransition(label = "shimmer").animateFloat(
        initialValue = -400f,
        targetValue = 1400f,
        animationSpec = infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Restart),
        label = "sweep",
    )
    background(
        Brush.linearGradient(
            colors = listOf(base, shine, base),
            start = Offset(sweep, 0f),
            end = Offset(sweep + 400f, 120f),
        ),
    )
}

/** One placeholder block — a line of text, a figure, a chip. */
@Composable
fun SkeletonBlock(
    modifier: Modifier = Modifier,
    height: Dp = 14.dp,
    shape: Shape = RoundedCornerShape(6.dp),
) {
    Box(modifier.height(height).clip(shape).shimmer())
}

/** A card of the kind most lists show: a title, a status pill, two lines below. */
@Composable
fun SkeletonCard(modifier: Modifier = Modifier, lines: Int = 2) {
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SkeletonBlock(Modifier.weight(1f).padding(end = 48.dp), height = 18.dp)
                SkeletonBlock(Modifier.size(width = 64.dp, height = 20.dp), height = 20.dp, shape = RoundedCornerShape(10.dp))
            }
            repeat(lines) { i ->
                SkeletonBlock(Modifier.fillMaxWidth(if (i % 2 == 0) 0.85f else 0.6f))
            }
        }
    }
}

/** A list still loading: a few cards, enough to fill the first screen. */
@Composable
fun SkeletonList(modifier: Modifier = Modifier, count: Int = 6, lines: Int = 2) {
    Column(
        modifier.fillMaxWidth().padding(16.dp).semantics { contentDescription = "Đang tải" },
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        repeat(count) { SkeletonCard(lines = lines) }
    }
}

/**
 * A details card still loading: label on the left, value on the right, a rule
 * between rows — the shape of a customer's Thông tin card.
 */
@Composable
fun SkeletonDetailCard(modifier: Modifier = Modifier, rows: Int = 10) {
    Card(
        modifier.fillMaxWidth().padding(16.dp).semantics { contentDescription = "Đang tải" },
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(Modifier.padding(horizontal = 12.dp)) {
            repeat(rows) { i ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp),
                ) {
                    SkeletonBlock(Modifier.fillMaxWidth(0.3f))
                    Spacer(Modifier.weight(1f))
                    // Of what the label leaves: values vary in length, so do these.
                    SkeletonBlock(Modifier.fillMaxWidth(if (i % 3 == 0) 0.6f else 0.4f))
                }
                if (i < rows - 1) HorizontalDivider()
            }
        }
    }
}

/** Tổng quan still loading: the day's figures, the month's bars, the chart. */
@Composable
fun SkeletonDashboard(modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize().padding(16.dp).semantics { contentDescription = "Đang tải" },
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Trong ngày: the revenue, then three figures side by side.
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SkeletonBlock(Modifier.fillMaxWidth(0.3f), height = 18.dp)
                SkeletonBlock(Modifier.fillMaxWidth(0.5f), height = 32.dp)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    repeat(3) {
                        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            SkeletonBlock(Modifier.fillMaxWidth(0.7f))
                            SkeletonBlock(Modifier.fillMaxWidth(0.4f), height = 26.dp)
                        }
                    }
                }
            }
        }
        // Tháng này: three progress rows.
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                SkeletonBlock(Modifier.fillMaxWidth(0.3f), height = 18.dp)
                repeat(3) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SkeletonBlock(Modifier.weight(1f).padding(end = 16.dp))
                        SkeletonBlock(Modifier.size(width = 36.dp, height = 36.dp), height = 36.dp, shape = CircleShape)
                    }
                }
            }
        }
        // The chart.
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SkeletonBlock(Modifier.fillMaxWidth(0.4f), height = 18.dp)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.Bottom,
                    modifier = Modifier.fillMaxWidth().height(120.dp),
                ) {
                    listOf(0.4f, 0.7f, 0.55f, 0.9f, 0.6f, 0.8f, 0.5f).forEach { h ->
                        SkeletonBlock(Modifier.weight(1f), height = 120.dp * h)
                    }
                }
            }
        }
    }
}

/**
 * A form still loading: a label and an input box per field, then the button
 * pair at the bottom — the shape of the note, feedback, survey and new
 * customer forms.
 */
@Composable
fun SkeletonForm(modifier: Modifier = Modifier, fields: Int = 4) {
    Column(
        modifier.fillMaxWidth().padding(16.dp).semantics { contentDescription = "Đang tải" },
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                repeat(fields) { i ->
                    if (i > 0) Spacer(Modifier.height(4.dp))
                    SkeletonBlock(Modifier.fillMaxWidth(if (i % 2 == 0) 0.35f else 0.5f))
                    SkeletonBlock(Modifier.fillMaxWidth(), height = 52.dp, shape = RoundedCornerShape(10.dp))
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            repeat(2) {
                SkeletonBlock(Modifier.weight(1f), height = 48.dp, shape = RoundedCornerShape(24.dp))
            }
        }
    }
}

/** A map still loading: the whole area shimmering, a card at its foot. */
@Composable
fun SkeletonMap(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().shimmer().semantics { contentDescription = "Đang tải" }) {
        Card(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(16.dp),
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SkeletonBlock(Modifier.fillMaxWidth(0.6f), height = 18.dp)
                SkeletonBlock(Modifier.fillMaxWidth(0.85f))
            }
        }
    }
}
