package com.tinhcd.myesalessfa.feature.incall.steps

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
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
import com.tinhcd.myesalessfa.core.ui.formatDong
import com.tinhcd.myesalessfa.domain.model.PricedProduct

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TakeOrderScreen(
    onDone: () -> Unit,
    viewModel: TakeOrderViewModel = hiltViewModel(),
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
                        Text("Dat hang")
                        val subtitle = listOfNotNull(
                            state.customerName.ifBlank { null },
                            state.order.lines.size.takeIf { it > 0 }?.let { "$it mat hang" },
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
                    state.error ?: "Danh muc san pham trong. Dang nhap lai de tai ve.",
                    onRetry = viewModel::load,
                )

                else -> Column(
                    Modifier
                        .fillMaxSize()
                        .imePadding(),
                ) {
                    OutlinedTextField(
                        value = state.query,
                        onValueChange = viewModel::onQueryChange,
                        label = { Text("Tim san pham") },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    )

                    val visible = state.visible
                    if (visible.isEmpty()) {
                        ErrorBox("Khong tim thay san pham nao")
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
                                ProductRow(
                                    product = product,
                                    state = state,
                                    onUnitChange = { viewModel.onUnitChange(product, it) },
                                    onQtyChange = { viewModel.onQtyChange(product, it) },
                                )
                            }
                        }
                    }

                    OrderFooter(
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
private fun ProductRow(
    product: PricedProduct,
    state: TakeOrderUiState,
    onUnitChange: (String) -> Unit,
    onQtyChange: (Int) -> Unit,
) {
    val unit = state.unitFor(product)
    val qty = state.order.quantityOf(product.product.id, unit.unit.uomCode)
    val line = state.order.lines.firstOrNull {
        it.productId == product.product.id && it.uomCode == unit.unit.uomCode
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(
                product.product.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            Text(
                product.product.code,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // Only worth showing when there is a choice to make.
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
                        "${formatDong(unit.price)} / ${unit.unit.uomName}",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    // The base quantity is what the warehouse picks, so a rep
                    // ordering cases can see what it comes to in pieces.
                    if (unit.unit.conversionRate > 1) {
                        Text(
                            "1 ${unit.unit.uomName} = ${unit.unit.conversionRate} " +
                                product.product.baseUomCode.lowercase(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                QtyStepper(qty = qty, onQtyChange = onQtyChange)
            }

            if (line != null) {
                Text(
                    "${line.qty} x ${formatDong(line.unitPrice)} = " +
                        "${formatDong(line.grossAmount)} (+VAT ${formatDong(line.vatAmount)})",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun QtyStepper(qty: Int, onQtyChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        FilledIconButton(
            onClick = { onQtyChange(qty - 1) },
            enabled = qty > 0,
            modifier = Modifier.size(36.dp),
        ) { Icon(Icons.Default.Remove, contentDescription = "Giam") }

        OutlinedTextField(
            value = if (qty == 0) "" else qty.toString(),
            onValueChange = { typed ->
                // Ignore anything that is not a number rather than clearing the
                // field: a rep fat-fingering a letter should not lose the qty.
                val digits = typed.filter { it.isDigit() }.take(5)
                onQtyChange(digits.toIntOrNull() ?: 0)
            },
            placeholder = { Text("0", textAlign = TextAlign.Center) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier
                .padding(horizontal = 6.dp)
                .width(72.dp),
        )

        FilledIconButton(
            onClick = { onQtyChange(qty + 1) },
            modifier = Modifier.size(36.dp),
        ) { Icon(Icons.Default.Add, contentDescription = "Tang") }
    }
}

@Composable
private fun OrderFooter(
    state: TakeOrderUiState,
    onSubmit: () -> Unit,
    onBack: () -> Unit,
) {
    Surface(shadowElevation = 8.dp) {
        Column(Modifier.padding(16.dp)) {
            AmountRow("Tam tinh", formatDong(state.order.subTotal))
            AmountRow("VAT", formatDong(state.order.vatAmount))
            AmountRow(
                label = "Tong cong",
                value = formatDong(state.order.totalAmount),
                emphasise = true,
            )

            if (state.error != null) {
                Text(
                    state.error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            PrimaryButton(
                text = "Gui don hang",
                onClick = onSubmit,
                enabled = state.order.canSubmit,
                loading = state.submitting,
                modifier = Modifier.padding(top = 8.dp),
            )

            OutlinedButton(
                onClick = onBack,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
            ) { Text("Quay lai") }
        }
    }
}

@Composable
private fun AmountRow(label: String, value: String, emphasise: Boolean = false) {
    Row(Modifier.fillMaxWidth()) {
        Text(
            label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            color = if (emphasise) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (emphasise) FontWeight.Bold else FontWeight.Normal,
        )
    }
}
