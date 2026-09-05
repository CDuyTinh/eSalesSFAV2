package com.tinhcd.myesalessfa.feature.incall.steps

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * The quantity control both halves of a visit's product work share.
 *
 * Kiểm tồn counts with it and Đặt hàng orders with it, against the same
 * catalogue, minutes apart. They used different controls — a 32dp bordered box
 * between two tonal circles here, a 56dp OutlinedTextField there — which made
 * the same product look like two different rows depending on which step the rep
 * had opened.
 *
 * A bordered box around a bare text field rather than an OutlinedTextField,
 * whose 56dp minimum height and internal padding took half a card.
 *
 * @param qty null means "nothing entered", which the two screens read
 *  differently: not counted yet, or not ordered. [placeholder] names it.
 */
@Composable
internal fun QtyStepper(
    qty: Int?,
    onQtyChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "-",
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = modifier,
    ) {
        StepperButton(
            icon = Icons.Default.Remove,
            description = "Giảm",
            enabled = (qty ?: 0) > 0,
            onClick = { onQtyChange((qty ?: 0) - 1) },
        )

        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .padding(horizontal = 8.dp)
                .width(52.dp)
                .height(32.dp)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp)),
        ) {
            BasicTextField(
                value = qty?.toString() ?: "",
                onValueChange = { typed ->
                    val digits = typed.filter { it.isDigit() }.take(5)
                    onQtyChange(digits.toIntOrNull() ?: 0)
                },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    color = valueColor,
                    textAlign = TextAlign.Center,
                ),
                cursorBrush = SolidColor(valueColor),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                decorationBox = { field ->
                    Box(contentAlignment = Alignment.Center) {
                        if (qty == null) {
                            Text(
                                placeholder,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        field()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        StepperButton(
            icon = Icons.Default.Add,
            description = "Tăng",
            enabled = true,
            onClick = { onQtyChange((qty ?: 0) + 1) },
        )
    }
}

/**
 * 30dp, tonal rather than filled.
 *
 * The filled 36dp pair this replaces was the loudest thing on a card whose
 * subject is a number — two solid blue discs either side of the figure they were
 * meant to be adjusting. Still comfortably tappable; the row is 32dp tall and a
 * thumb lands on the whole of it.
 */
@Composable
private fun StepperButton(
    icon: ImageVector,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = CircleShape,
        color = if (enabled) scheme.secondaryContainer else scheme.surfaceVariant,
        modifier = Modifier
            .size(30.dp)
            .clickable(enabled = enabled, onClick = onClick),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                icon,
                contentDescription = description,
                tint = if (enabled) scheme.onSecondaryContainer else scheme.outline,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}
