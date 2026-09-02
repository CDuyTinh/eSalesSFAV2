package com.tinhcd.myesalessfa.feature.incall.steps

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
                    LegendBoard(
                        mustStockOnly = state.mustStockOnly,
                        requiredCount = compliance.required.takeIf { it > 0 },
                        uncheckedCount = compliance.unchecked,
                        onMustStockOnlyChange = viewModel::onMustStockOnlyChange,
                    )

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

/**
 * The board above the list: what the three numbers on every row mean.
 *
 * Copied from the legacy screen, where the row carries three bare figures and a
 * legend at the top is the only thing that names them. That trade is right for a
 * list of forty products — labelling each row three times costs a line per row
 * and says the same thing forty times.
 *
 * The legacy board also carries a picker for counting by total or by expiry
 * date. There is no second mode here: lots and expiry dates are deliberately out
 * of this build, for the reasons the site-stock screen records.
 */
@Composable
private fun LegendBoard(
    mustStockOnly: Boolean,
    requiredCount: Int?,
    uncheckedCount: Int,
    onMustStockOnlyChange: (Boolean) -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                LegendDot("Kỳ trước", PreviousInk)
                LegendDot("Tồn hiện tại", CountedGreen)
                LegendDot("Định mức", ParBlue)
            }

            if (requiredCount != null) {
                Spacer(Modifier.size(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(
                        selected = mustStockOnly,
                        onClick = { onMustStockOnlyChange(!mustStockOnly) },
                        label = { Text("Chỉ hàng bắt buộc ($requiredCount)") },
                    )
                    Spacer(Modifier.width(8.dp))
                    if (uncheckedCount > 0) {
                        Text(
                            "còn $uncheckedCount chưa kiểm",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LegendDot(name: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(8.dp)
                .background(color, CircleShape),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = name,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(12.dp)) {
            ProductThumb(par != null)

            Spacer(Modifier.width(12.dp))

            Column(Modifier.weight(1f)) {
                // Name, then the out-of-stock toggle on the right, as the legacy
                // row has it.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        product.product.name,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f),
                    )

                    // Kept from before the legacy layout arrived. That app has no
                    // notion of un-counting — every product there either has a
                    // number or was never touched — but this one distinguishes
                    // "counted zero" from "not counted", and compliance is
                    // measured over what was checked. Without this a rep who
                    // typed a wrong figure could only replace it, never withdraw
                    // it, and their slip would land on the outlet's record.
                    if (line != null && line.qty > 0) {
                        IconButton(
                            onClick = onClear,
                            modifier = Modifier.size(32.dp),
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Bỏ kiểm sản phẩm này",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }

                    OutOfStockToggle(
                        checked = line?.qty == 0,
                        onToggle = { if (line?.qty == 0) onClear() else onQtyChange(0) },
                    )
                }

                // Code and unit separated by a rule, again as there. The par
                // level moved out of this line and into the third figure below,
                // where it lines up with the other two numbers.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 2.dp),
                ) {
                    Text(
                        product.product.code,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Box(
                        Modifier
                            .padding(horizontal = 6.dp)
                            .size(width = 1.dp, height = 10.dp)
                            .background(MaterialTheme.colorScheme.outlineVariant),
                    )
                    Text(
                        unit.unit.uomName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (unit.unit.conversionRate > 1) {
                        Text(
                            " = ${unit.unit.conversionRate} $baseUom",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
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

                // The three figures, in the legend's order and its colours:
                // what was here last time, what is here now, what should be.
                // Only the middle one is the rep's to write.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                ) {
                    // Blank, not a dash, when there is no figure. A dash pinned
                    // to the far edge of the row reads as a stray mark rather
                    // than as "nothing recorded", and on a product that is
                    // neither stocked before nor required there would be two of
                    // them framing the stepper.
                    Text(
                        text = prevBase?.toString().orEmpty(),
                        style = MaterialTheme.typography.bodyLarge,
                        color = PreviousInk,
                    )

                    CountStepper(qty = line?.qty, onQtyChange = onQtyChange)

                    Text(
                        text = par?.toString().orEmpty(),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.End,
                        color = ParBlue,
                    )
                }

                // Below the figures rather than beside them: this is the reading
                // of the three numbers, and it only exists once one is entered.
                if (line != null) {
                    val movement = when {
                        line.isNewlyOutOfStock ->
                            "Hết hàng - kỳ trước còn ${line.prevBaseQty} $baseUom"

                        line.soldSinceCount > 0 ->
                            "Bán ${line.soldSinceCount} $baseUom từ kỳ trước"

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

                    // The replenishment figure: the gap between the shelf and the
                    // par level. Shown only when there is one, so it reads as an
                    // action rather than as decoration.
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
}

/**
 * The square the legacy row puts a product photo in.
 *
 * A placeholder, because no photo reaches here: `product.image_url` exists in the
 * schema but the catalogue does not select it, the cached ProductEntity has no
 * column for it, and every seeded product's is null. Carrying it would be an RPC
 * change, a DTO field, a Room migration and a domain field — worth doing when
 * there are photos to show, not to fill this square today.
 *
 * It still earns its place: the tint marks a must-stock product, which is what
 * the "BẮT BUỘC" tag used to say in words above the name.
 */
@Composable
private fun ProductThumb(required: Boolean) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (required) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        modifier = Modifier.size(48.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                Icons.Default.Inventory2,
                contentDescription = if (required) "Hàng bắt buộc" else null,
                tint = if (required) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

/**
 * OOS, the legacy's own label, with its circle that fills when set.
 *
 * Writes a count of zero rather than a flag of its own, because zero already
 * means "looked, found none" here and a second way of saying it would be a
 * second thing to keep in step. Pressing it again clears the count entirely,
 * which is the other state — nobody looked.
 */
@Composable
private fun OutOfStockToggle(checked: Boolean, onToggle: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onToggle)
            .padding(horizontal = 6.dp, vertical = 4.dp),
    ) {
        Text(
            text = "OOS",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(6.dp))
        Icon(
            imageVector = if (checked) {
                Icons.Default.CheckCircle
            } else {
                Icons.Default.RadioButtonUnchecked
            },
            contentDescription = "Hết hàng",
            tint = if (checked) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.outline
            },
            modifier = Modifier.size(18.dp),
        )
    }
}

/** Legacy's three figure colours: #0D0C22 read, #04A489 written, #2D2DFE owed. */
private val PreviousInk = Color(0xFF0D0C22)
private val CountedGreen = Color(0xFF04A489)
private val ParBlue = Color(0xFF2D2DFE)

/**
 * Null [qty] means the product has not been checked; 0 means checked and empty.
 * The field is blank in the first case and shows "0" in the second, so the two
 * are distinguishable at a glance.
 */
@Composable
private fun CountStepper(
    qty: Int?,
    onQtyChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = modifier,
    ) {
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
