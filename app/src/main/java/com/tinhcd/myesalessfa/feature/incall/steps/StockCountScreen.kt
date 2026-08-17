package com.tinhcd.myesalessfa.feature.incall.steps

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tinhcd.myesalessfa.core.ui.ErrorBox
import com.tinhcd.myesalessfa.core.ui.LoadingBox
import com.tinhcd.myesalessfa.core.ui.PrimaryButton
import com.tinhcd.myesalessfa.domain.model.PricedProduct

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StockCountScreen(
    onDone: () -> Unit,
    viewModel: StockCountViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(state.finished) {
        if (state.finished) onDone()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Kiểm tồn cửa hàng")
                        val subtitle = listOfNotNull(
                            state.customerName.ifBlank { null },
                            state.count.countedProducts
                                .takeIf { it > 0 }
                                ?.let { "đã kiểm $it mặt hàng" },
                        ).joinToString(" - ")
                        if (subtitle.isNotBlank()) {
                            Text(subtitle, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding)) {
            when {
                state.loading -> LoadingBox()

                state.catalogue.isEmpty() -> ErrorBox(
                    state.error ?: "Danh mục sản phẩm trống. Đăng nhập lại để tải về.",
                    onRetry = viewModel::load,
                )

                else -> Column(
                    Modifier
                        .fillMaxSize()
                        .imePadding(),
                ) {
                    if (state.previousUnavailable) {
                        // Worth saying out loud: without it the rep may read a
                        // missing "last time" as the outlet never having been
                        // counted, which is a different conclusion entirely.
                        Text(
                            "Không tải được số liệu kỳ trước. Vẫn kiểm được, chỉ không so sánh được.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                        )
                    }

                    OutlinedTextField(
                        value = state.query,
                        onValueChange = viewModel::onQueryChange,
                        label = { Text("Tìm sản phẩm") },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    )

                    // Only worth offering when the outlet actually owes something.
                    val compliance = state.count.compliance
                    if (compliance.required > 0) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 12.dp),
                        ) {
                            FilterChip(
                                selected = state.mustStockOnly,
                                onClick = { viewModel.onMustStockOnlyChange(!state.mustStockOnly) },
                                label = { Text("Chỉ hàng bắt buộc (${compliance.required})") },
                            )
                            Spacer(Modifier.width(8.dp))
                            if (compliance.unchecked > 0) {
                                Text(
                                    "còn ${compliance.unchecked} chưa kiểm",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }

                    val visible = state.visible
                    if (visible.isEmpty()) {
                        ErrorBox("Không tìm thấy sản phẩm nào")
                    } else {
                        LazyColumn(
                            modifier = Modifier.weight(1f),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                horizontal = 12.dp,
                                vertical = 4.dp,
                            ),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(visible, key = { it.product.id }) { product ->
                                StockRow(
                                    product = product,
                                    state = state,
                                    onUnitChange = { viewModel.onUnitChange(product, it) },
                                    onQtyChange = { viewModel.onQtyChange(product, it) },
                                    onClear = { viewModel.onClearProduct(product) },
                                )
                            }
                        }
                    }

                    StockFooter(
                        state = state,
                        onSubmit = viewModel::submit,
                        onBack = onDone,
                    )
                }
            }
        }
    }
}

@Composable
private fun StockRow(
    product: PricedProduct,
    state: StockCountUiState,
    onUnitChange: (String) -> Unit,
    onQtyChange: (Int) -> Unit,
    onClear: () -> Unit,
) {
    val unit = state.unitFor(product)
    val line = state.count.lineFor(product.product.id, unit.unit.uomCode)
    val prevBase = state.previous[product.product.id]
    val baseUom = product.product.baseUomCode.lowercase()
    val par = state.parFor(product)

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Marked before the name, so a rep scanning the list sees the
                        // obligation without reading each row.
                        if (par != null) {
                            Text(
                                "BẮT BUỘC",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(end = 6.dp),
                            )
                        }
                        Text(
                            product.product.name,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                    Text(
                        product.product.code +
                            if (par != null) "  -  định mức $par $baseUom" else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // Only offered once something has been recorded: clearing is how
                // the rep undoes a count, which is different from counting zero.
                if (line != null) {
                    IconButton(onClick = onClear) {
                        Icon(Icons.Default.Close, contentDescription = "Bỏ qua sản phẩm này")
                    }
                }
            }

            if (product.units.size > 1) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(top = 6.dp),
                ) {
                    product.units.forEach { option ->
                        FilterChip(
                            selected = option.unit.uomCode == unit.unit.uomCode,
                            onClick = { onUnitChange(option.unit.uomCode) },
                            label = { Text(option.unit.uomName) },
                        )
                    }
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        if (prevBase != null) {
                            "Kỳ trước: $prevBase $baseUom"
                        } else {
                            "Chưa kiểm lần nào"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (unit.unit.conversionRate > 1) {
                        Text(
                            "1 ${unit.unit.uomName} = ${unit.unit.conversionRate} $baseUom",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                CountStepper(
                    qty = line?.qty,
                    onQtyChange = onQtyChange,
                )
            }

            if (line != null) {
                val movement = when {
                    line.isNewlyOutOfStock -> "Hết hàng - kỳ trước còn ${line.prevBaseQty} $baseUom"
                    line.soldSinceCount > 0 -> "Bán ${line.soldSinceCount} $baseUom từ kỳ trước"
                    else -> "Tồn ${line.baseQty} $baseUom"
                }
                Text(
                    movement,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (line.isNewlyOutOfStock) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                    modifier = Modifier.padding(top = 6.dp),
                )

                // The replenishment figure: the gap between the shelf and the par
                // level. Shown only when there is one, so it reads as an action
                // rather than as decoration.
                if (line.shortfallBaseQty > 0) {
                    Text(
                        "Thiếu ${line.shortfallBaseQty} $baseUom so với định mức",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

/**
 * Null [qty] means the product has not been checked; 0 means checked and empty.
 * The field is blank in the first case and shows "0" in the second, so the two
 * are distinguishable at a glance.
 */
@Composable
private fun CountStepper(qty: Int?, onQtyChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        FilledIconButton(
            onClick = { onQtyChange((qty ?: 0) - 1) },
            enabled = (qty ?: 0) > 0,
            modifier = Modifier.size(36.dp),
        ) { Icon(Icons.Default.Remove, contentDescription = "Giảm") }

        OutlinedTextField(
            value = qty?.toString() ?: "",
            onValueChange = { typed ->
                val digits = typed.filter { it.isDigit() }.take(5)
                onQtyChange(digits.toIntOrNull() ?: 0)
            },
            placeholder = { Text("-", textAlign = TextAlign.Center) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier
                .padding(horizontal = 6.dp)
                .width(72.dp),
        )

        FilledIconButton(
            onClick = { onQtyChange((qty ?: 0) + 1) },
            modifier = Modifier.size(36.dp),
        ) { Icon(Icons.Default.Add, contentDescription = "Tăng") }
    }
}

@Composable
private fun StockFooter(
    state: StockCountUiState,
    onSubmit: () -> Unit,
    onBack: () -> Unit,
) {
    Surface(shadowElevation = 8.dp) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth()) {
                Text(
                    "Đã kiểm",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "${state.count.countedProducts} mặt hàng",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                )
            }

            if (state.count.outOfStockCount > 0) {
                Row(Modifier.fillMaxWidth()) {
                    Text(
                        "Hết hàng",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "${state.count.outOfStockCount} mặt hàng",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            val compliance = state.count.compliance
            if (compliance.required > 0) {
                Row(Modifier.fillMaxWidth()) {
                    Text(
                        "Hàng bắt buộc có sẵn",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        // Measured over what was checked, not over what was
                        // required: a SKU nobody looked at is not evidence either
                        // way, and counting it as absent would put the rep's
                        // omission on the outlet's record.
                        "${compliance.available}/${compliance.available + compliance.outOfStock}" +
                            " (${compliance.availabilityPercent}%)",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold,
                        color = if (compliance.outOfStock > 0) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                }

                if (!compliance.isComplete) {
                    Text(
                        "Còn ${compliance.unchecked} mặt hàng bắt buộc chưa kiểm",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                if (state.count.totalShortfallBaseQty > 0) {
                    Text(
                        "Cần bổ sung ${state.count.totalShortfallBaseQty} đơn vị để đạt định mức",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (state.error != null) {
                Text(
                    state.error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            PrimaryButton(
                text = "Gửi phiếu kiểm tồn",
                onClick = onSubmit,
                enabled = state.count.canSubmit,
                loading = state.submitting,
                modifier = Modifier.padding(top = 8.dp),
            )

            OutlinedButton(
                onClick = onBack,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
            ) { Text("Quay lại") }
        }
    }
}
