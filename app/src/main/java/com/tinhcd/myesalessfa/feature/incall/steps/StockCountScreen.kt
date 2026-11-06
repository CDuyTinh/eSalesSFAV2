package com.tinhcd.myesalessfa.feature.incall.steps

import androidx.activity.compose.BackHandler
import com.tinhcd.myesalessfa.R
import androidx.compose.ui.res.stringResource
import com.tinhcd.myesalessfa.core.ui.theme.status
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tinhcd.myesalessfa.core.ui.ButtonPair
import com.tinhcd.myesalessfa.core.ui.ErrorBox
import com.tinhcd.myesalessfa.core.ui.PrimaryButton
import com.tinhcd.myesalessfa.core.ui.SearchBox
import com.tinhcd.myesalessfa.core.ui.SkeletonList
import com.tinhcd.myesalessfa.core.ui.theme.brand
import com.tinhcd.myesalessfa.domain.model.PricedProduct
import com.tinhcd.myesalessfa.domain.model.StockScope

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StockCountScreen(
    onDone: () -> Unit,
    viewModel: StockCountViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var filterOpen by remember { mutableStateOf(false) }
    var confirmLeave by remember { mutableStateOf(false) }

    LaunchedEffect(state.finished) {
        if (state.finished) onDone()
    }

    // Nothing is written until submit, so leaving with lines entered throws the
    // whole sheet away. The app this replaces asks first; this one used to let a
    // stray back press cost a rep the shop's entire count.
    val leave = { if (state.hasUnsavedWork) confirmLeave = true else onDone() }

    BackHandler(enabled = state.hasUnsavedWork) { confirmLeave = true }

    Scaffold(
        topBar = {
            StepHeader(
                title = stringResource(R.string.sfa_50),
                subtitle = listOfNotNull(
                    state.customerName.ifBlank { null },
                    state.count.countedProducts
                        .takeIf { it > 0 }
                        ?.let { stringResource(R.string.sft_58, it) },
                ).joinToString(" - "),
                onBack = leave,
                actions = {
                    HeaderAction(
                        icon = Icons.Default.FilterList,
                        description = stringResource(R.string.order_filter_group),
                        badge = state.categories.size.takeIf { it > 0 }?.toString(),
                        tint = MaterialTheme.brand.onHeader,
                        onClick = { filterOpen = true },
                    )
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding)) {
            when {
                state.loading -> SkeletonList(count = 7, lines = 1)

                state.catalogue.isEmpty() -> ErrorBox(
                    state.error ?: stringResource(R.string.order_catalogue_empty),
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
                            stringResource(R.string.sfb_119),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                        )
                    }

                    SearchBox(
                        query = state.query,
                        onQueryChanged = viewModel::onQueryChange,
                        placeholder = stringResource(R.string.sfa_51),
                        // Outlined here, unlike on the blue header bands, because
                        // this one sits on the page ground: a white box on a
                        // near-white page needs an edge to read as a field.
                        modifier = Modifier
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                            .border(
                                1.dp,
                                MaterialTheme.colorScheme.outlineVariant,
                                RoundedCornerShape(12.dp),
                            ),
                    )

                    LegendBoard(
                        state = state,
                        onScopeChange = viewModel::onScopeChange,
                    )

                    val visible = state.visible
                    if (visible.isEmpty()) {
                        ErrorBox(stringResource(R.string.order_no_products))
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
                        onBack = leave,
                    )
                }
            }
        }
    }

    if (filterOpen) {
        CategorySheet(
            all = state.allCategories,
            selected = state.categories,
            onToggle = viewModel::onCategoryToggle,
            onClear = viewModel::clearCategories,
            onDismiss = { filterOpen = false },
        )
    }

    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text(stringResource(R.string.sfa_52)) },
            text = {
                Text(
                    stringResource(R.string.sft_59, state.count.countedProducts) +
                        stringResource(R.string.sfb_63),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmLeave = false
                        onDone()
                    },
                ) { Text(stringResource(R.string.sfa_14)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmLeave = false }) { Text(stringResource(R.string.sfa_15)) }
            },
        )
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
    state: StockCountUiState,
    onScopeChange: (StockScope) -> Unit,
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
                LegendDot(stringResource(R.string.sfb_120), PreviousInk)
                LegendDot(stringResource(R.string.sfb_121), CountedGreen)
                LegendDot(stringResource(R.string.sfb_122), ParBlue)
            }

            Spacer(Modifier.size(10.dp))

            // Three chips rather than the one "chỉ hàng bắt buộc" toggle this
            // had. The default is the outlet's own recent purchases, which is
            // the sheet the legacy app builds and the only one a rep can work
            // down without scrolling past everything the shop never stocks.
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState()),
            ) {
                StockScope.entries.forEach { option ->
                    FilterChip(
                        selected = option == state.scope,
                        onClick = { onScopeChange(option) },
                        label = { Text("${option.label} (${state.sizeOf(option)})") },
                    )
                }
            }

            // "Còn N hàng bắt buộc chưa kiểm" is not repeated here. It lives in
            // the footer beside the figure it explains, and the footer is the
            // half of the screen that stays put while the rep scrolls.

            // Said once, here, rather than left for the rep to infer from a chip
            // whose count equals the whole catalogue's.
            if (state.purchased.isEmpty()) {
                Spacer(Modifier.size(6.dp))
                Text(
                    stringResource(R.string.sfb_123),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
                                contentDescription = stringResource(R.string.sfa_53),
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

                    QtyStepper(
                        qty = line?.qty,
                        onQtyChange = onQtyChange,
                        // Green, matching the legend dot for the figure the rep
                        // is the one writing.
                        valueColor = CountedGreen,
                    )

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
                            stringResource(R.string.sft_60, line.prevBaseQty, baseUom)

                        line.soldSinceCount > 0 ->
                            stringResource(R.string.sft_61, line.soldSinceCount, baseUom)

                        else -> stringResource(R.string.sft_62, line.baseQty, baseUom)
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
                            stringResource(R.string.sft_63, line.shortfallBaseQty, baseUom),
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
                contentDescription = if (required) stringResource(R.string.sfb_124) else null,
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
            contentDescription = stringResource(R.string.sfa_54),
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
private val PreviousInk: Color
    @Composable get() = MaterialTheme.colorScheme.onSurface
private val CountedGreen: Color
    @Composable get() = MaterialTheme.status.success
private val ParBlue: Color
    @Composable get() = MaterialTheme.status.info

/**
 * The bar the count is read off before it is sent.
 *
 * Three figures on one line rather than three label-and-value rows, and the two
 * buttons side by side rather than stacked. What was here took a third of the
 * screen to say four short things, and gave "Quay lại" a full-width outline —
 * the same visual weight as the action that actually files the count.
 */
@Composable
private fun StockFooter(
    state: StockCountUiState,
    onSubmit: () -> Unit,
    onBack: () -> Unit,
) {
    val compliance = state.count.compliance
    val scheme = MaterialTheme.colorScheme

    Surface(
        shadowElevation = 12.dp,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        color = scheme.surface,
    ) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 14.dp)) {
            Row(Modifier.fillMaxWidth()) {
                FooterStat(
                    value = state.count.countedProducts.toString(),
                    label = stringResource(R.string.sfa_55),
                )
                FooterStat(
                    value = state.count.outOfStockCount.toString(),
                    label = stringResource(R.string.sfa_54),
                    tint = if (state.count.outOfStockCount > 0) scheme.error else null,
                )
                if (compliance.required > 0) {
                    FooterStat(
                        // Measured over what was checked, not over what was
                        // required: a SKU nobody looked at is not evidence
                        // either way, and counting it as absent would put the
                        // rep's omission on the outlet's record.
                        value = "${compliance.available}/" +
                            "${compliance.available + compliance.outOfStock}",
                        label = stringResource(R.string.sfa_56),
                        tint = if (compliance.outOfStock > 0) scheme.error else null,
                    )
                }
            }

            // The two notes and any error, together and quiet. They are what to
            // do next, not what was counted, so they sit under the figures
            // rather than between them.
            val notes = listOfNotNull(
                stringResource(R.string.sft_64, compliance.unchecked)
                    .takeIf { compliance.required > 0 && !compliance.isComplete },
                stringResource(R.string.sft_65, state.count.totalShortfallBaseQty)
                    .takeIf { state.count.totalShortfallBaseQty > 0 },
                state.error,
            )
            if (notes.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                notes.forEach { note ->
                    Text(
                        note,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (note == state.error) scheme.error else scheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            // 44dp rather than the app's usual 52. This bar carries two buttons
            // above a list the rep is working down, and the list is what they
            // came for.
            ButtonPair(
                text = stringResource(R.string.sfa_57),
                onClick = onSubmit,
                onBack = onBack,
                enabled = state.count.canSubmit,
                loading = state.submitting,
                height = 44.dp,
            )
        }
    }
}

/**
 * One figure and its name, stacked. The number leads because it is what the rep
 * is checking; the name is only there to say which number it is.
 */
@Composable
private fun RowScope.FooterStat(value: String, label: String, tint: Color? = null) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.weight(1f),
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = tint ?: MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 2,
        )
    }
}
