package com.tinhcd.myesalessfa.feature.incall.steps

import androidx.compose.foundation.Canvas
import com.tinhcd.myesalessfa.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.tinhcd.myesalessfa.core.ui.theme.brand

/**
 * Where the rep is in placing the order — Giỏ hàng, Xác nhận đơn hàng, Thông
 * tin đơn hàng — as the legacy's ProgressOrder draws it in the header: a
 * circle per step joined by dashes, the current one ringed, the ones behind it
 * filled, the ones ahead faint.
 */
@Composable
internal fun OrderSteps(current: Int, modifier: Modifier = Modifier) {
    val steps = listOf(
        Icons.Default.ShoppingCart to stringResource(R.string.order_basket),
        Icons.Default.LocalShipping to stringResource(R.string.step_confirm),
        Icons.Default.Info to stringResource(R.string.step_info),
    )
    val onHeader = MaterialTheme.brand.onHeader

    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        steps.forEachIndexed { i, (icon, label) ->
            StepDot(icon, label, reached = i <= current, isCurrent = i == current)
            if (i < steps.lastIndex) {
                Canvas(
                    Modifier
                        .weight(1f)
                        .padding(top = 19.dp)
                        .height(2.dp),
                ) {
                    drawLine(
                        color = onHeader.copy(alpha = if (i < current) 0.9f else 0.5f),
                        start = Offset(0f, size.height / 2),
                        end = Offset(size.width, size.height / 2),
                        strokeWidth = size.height,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)),
                    )
                }
            }
        }
    }
}

@Composable
private fun StepDot(icon: ImageVector, label: String, reached: Boolean, isCurrent: Boolean) {
    val brand = MaterialTheme.brand
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(
            Modifier
                .size(40.dp)
                .border(
                    width = if (isCurrent) 4.dp else 1.5.dp,
                    color = if (isCurrent) Color.White.copy(alpha = 0.45f) else brand.onHeader.copy(alpha = 0.6f),
                    shape = CircleShape,
                )
                .padding(if (isCurrent) 4.dp else 1.5.dp)
                .background(if (reached) Color.White else Color.Transparent, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = label,
                tint = if (reached) brand.header else brand.onHeader.copy(alpha = 0.7f),
                modifier = Modifier.size(18.dp),
            )
        }
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = brand.onHeader.copy(alpha = if (reached) 1f else 0.7f),
            textAlign = TextAlign.Center,
        )
    }
}
