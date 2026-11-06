package com.tinhcd.myesalessfa.core.ui

import androidx.compose.foundation.background
import com.tinhcd.myesalessfa.core.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tinhcd.myesalessfa.core.ui.theme.brand

@Composable
fun LoadingBox(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

/**
 * Failure state with a way out. A rep in the field cannot debug a stack trace;
 * they need to know it broke and be able to try again.
 */
@Composable
fun ErrorBox(
    message: String,
    onRetry: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Default.CloudOff,
            contentDescription = null,
            modifier = Modifier.size(40.dp),
            tint = MaterialTheme.colorScheme.error,
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
        if (onRetry != null) {
            OutlinedButton(onClick = onRetry) {
                Icon(Icons.Default.Refresh, contentDescription = null)
                Text(stringResource(R.string.core_1))
            }
        }
    }
}

/**
 * Dong, grouped the way it is written in Vietnam: 1.234.567 đ.
 *
 * Built by hand rather than through NumberFormat so the grouping does not follow
 * the device locale. A rep reading a total out to a customer must see the same
 * separators whatever language the phone is set to, and an English-locale phone
 * would otherwise render it 1,234,567.
 */
fun formatDong(amount: Long): String {
    val digits = amount.toString()
    val grouped = digits.reversed().chunked(3).joinToString(".").reversed()
    return "${grouped} đ"
}

/**
 * Tall by design: this gets tapped one-handed, outdoors, often in a hurry.
 */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    /**
     * 44 for a bar pinned to the bottom of a working screen — a count sheet, a
     * check-in, a visit's step list. Those bars sit over content the rep is
     * reading, and every dp the bar takes is a dp of it.
     *
     * The 52 default is for a button that *is* the screen: sign in, open the
     * selling day, finish a step whose form scrolls above it.
     */
    height: Dp = 52.dp,
) {
    Button(
        onClick = onClick,
        enabled = enabled && !loading,
        modifier = modifier
            .fillMaxWidth()
            .height(height),
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onPrimary,
            )
        } else {
            Text(text, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

/**
 * The one-line field every list in the app filters itself with.
 *
 * 44dp, which is under a Material text field's 56dp minimum and deliberately so:
 * it sits above a list the rep is trying to read, sometimes inside a header band
 * already carrying a title and two buttons, and a full-height field with a
 * floating label spends screen on a control that holds a few characters.
 *
 * Built from a BasicTextField because OutlinedTextField's height is a minimum
 * and its content padding is not reachable without rebuilding the decoration box
 * anyway.
 */
@Composable
fun SearchBox(
    query: String,
    onQueryChanged: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = modifier
            .fillMaxWidth()
            .height(44.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 12.dp, end = 4.dp),
        ) {
            Icon(
                Icons.Default.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
            Box(
                Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp),
            ) {
                if (query.isEmpty()) {
                    Text(
                        text = placeholder,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChanged,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChanged("") }) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = stringResource(R.string.core_2),
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

/**
 * The lower edge of every brand-blue toolbar: both corners rounded, as the
 * legacy app's headers are. Clip a TopAppBar with it; screens whose first card
 * overlaps the header draw a band in this shape instead.
 */
val HeaderShape = RoundedCornerShape(bottomStart = 20.dp, bottomEnd = 20.dp)

/**
 * The toolbar's colour carried a little way down in [HeaderShape], drawn behind
 * a screen's content so its first card sits over the edge (the dashboard's look).
 * Put it first in a Box under a square TopAppBar; a 16dp list padding then
 * leaves the card overlapping by the rest.
 */
@Composable
fun HeaderBand(modifier: Modifier = Modifier, depth: Dp = 44.dp) {
    Box(
        modifier
            .fillMaxWidth()
            .height(depth)
            .background(MaterialTheme.brand.header, HeaderShape),
    )
}

/**
 * Icon colours for a grid of menu tiles, as the report menu's illustrations
 * are coloured: one per tile, taken in turn, so neighbours never match. The
 * icon is drawn in the colour and its square in a pale wash of it.
 */
val TileColors = listOf(
    Color(0xFF2D6CDF), // blue
    Color(0xFFF57C00), // orange
    Color(0xFF04A489), // green
    Color(0xFF7B3FE4), // violet
    Color(0xFFE53935), // red
    Color(0xFF00A3BF), // teal
    Color(0xFFD81B60), // pink
    Color(0xFFF5A202), // amber
)

/** The [TileColors] entry for the tile at [index]. */
fun tileColor(index: Int): Color = TileColors[index.mod(TileColors.size)]

/**
 * The two buttons at the foot of a form, side by side and half the width each:
 * going back on the left, the step's action on the right.
 *
 * Side by side rather than stacked: two full-width buttons one above the other
 * take twice the height off the form above them, and the rep has to read both
 * to know which is which. Labels are kept short to fit half a phone; the
 * screen's title says what is being finished.
 */
@Composable
fun ButtonPair(
    text: String,
    onClick: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    backText: String = stringResource(R.string.core_3),
    enabled: Boolean = true,
    loading: Boolean = false,
    height: Dp = 46.dp,
) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(
            onClick = onBack,
            contentPadding = PairPadding,
            modifier = Modifier.weight(1f).height(height),
        ) { PairLabel(backText) }
        Button(
            onClick = onClick,
            enabled = enabled && !loading,
            contentPadding = PairPadding,
            modifier = Modifier.weight(1f).height(height),
        ) {
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            } else {
                PairLabel(text)
            }
        }
    }
}

private val PairPadding = PaddingValues(horizontal = 12.dp)

@Composable
private fun PairLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}
