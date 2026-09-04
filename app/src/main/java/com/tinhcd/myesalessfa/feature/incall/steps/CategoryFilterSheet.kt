package com.tinhcd.myesalessfa.feature.incall.steps

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Nhóm hàng — the category narrowing both product lists in a visit offer.
 *
 * Shared because Đặt hàng and Kiểm tồn ask the same question of the same
 * catalogue, and two copies would drift the moment one of them gained a
 * "select all".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CategorySheet(
    all: List<String>,
    selected: Set<String>,
    onToggle: (String) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(Modifier.navigationBarsPadding()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 20.dp, end = 12.dp, bottom = 12.dp),
            ) {
                Text(
                    "Nhóm hàng",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onClear) { Text("Bỏ lọc") }
            }
            HorizontalDivider()

            if (all.isEmpty()) {
                Text(
                    "Danh mục chưa chia nhóm",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(20.dp),
                )
            } else {
                LazyColumn {
                    items(all, key = { it }) { name ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                // The whole row, not just the box: a 20dp target
                                // in a sheet used one-handed reads as broken when
                                // the label is tapped and nothing happens.
                                .clickable { onToggle(name) }
                                .padding(horizontal = 12.dp, vertical = 4.dp),
                        ) {
                            Checkbox(
                                checked = name in selected,
                                onCheckedChange = { onToggle(name) },
                            )
                            Text(name, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

/**
 * A bar icon carrying a count, for the sort and filter buttons a step's header
 * puts beside its title.
 */
@Composable
internal fun HeaderAction(
    icon: ImageVector,
    description: String,
    badge: String?,
    tint: Color,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick) {
        BadgedBox(badge = { if (badge != null) Badge { Text(badge) } }) {
            Icon(icon, contentDescription = description, tint = tint)
        }
    }
}
