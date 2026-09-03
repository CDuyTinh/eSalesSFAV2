package com.tinhcd.myesalessfa.feature.incall.steps

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tinhcd.myesalessfa.core.ui.theme.MyeSalesTheme
import com.tinhcd.myesalessfa.core.ui.theme.brand

/**
 * The band every step of a visit sits under.
 *
 * Same gradient, same 20dp rounded bottom and same title weight as the Viếng
 * thăm tab's header, because a step is somewhere the rep arrives *from* that
 * tab. A plain app bar made each step look like a different app — the route
 * screen has a shape, and crossing into a step used to lose it.
 *
 * The route header carries a menu button in the leading slot; a step carries
 * back, which is the same slot doing the equivalent job one level down.
 */
@Composable
internal fun StepHeader(
    title: String,
    onBack: () -> Unit,
    subtitle: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
    below: (@Composable () -> Unit)? = null,
) {
    val brand = MaterialTheme.brand

    Box(
        Modifier
            .fillMaxWidth()
            .background(
                brush = Brush.linearGradient(
                    listOf(brand.header, lerp(brand.header, Color.White, 0.22f)),
                ),
                shape = RoundedCornerShape(bottomStart = 20.dp, bottomEnd = 20.dp),
            ),
    ) {
        Column(Modifier.statusBarsPadding()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .padding(horizontal = 4.dp)
                    .padding(bottom = 12.dp, top = 4.dp),
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Quay lại",
                        tint = brand.onHeader,
                    )
                }

                Column(Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        fontSize = 18.sp,
                        color = brand.onHeader,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!subtitle.isNullOrBlank()) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.labelSmall,
                            // Dimmed rather than a second full-strength line: it
                            // is context for the title, not a second title.
                            color = brand.onHeader.copy(alpha = 0.85f),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                actions()
            }

            // Inside the band rather than under it, the way the Viếng thăm header
            // carries its search box: a control that filters a list belongs to the
            // bar it is filtering from.
            if (below != null) {
                Box(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp)) {
                    below()
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun StepHeaderPreview() {
    MyeSalesTheme {
        Column {
            StepHeader(
                title = "Kiểm tồn cửa hàng",
                subtitle = "Tạp hóa Minh Anh - đã kiểm 3 mặt hàng",
                onBack = {},
            )
            StepHeader(title = "Đặt hàng", onBack = {})
        }
    }
}
