package com.tinhcd.myesalessfa.feature.incall.steps

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tinhcd.myesalessfa.core.ui.ErrorBox
import com.tinhcd.myesalessfa.core.ui.LoadingBox
import com.tinhcd.myesalessfa.core.ui.PrimaryButton
import com.tinhcd.myesalessfa.core.ui.formatDong
import com.tinhcd.myesalessfa.core.ui.theme.brand
import com.tinhcd.myesalessfa.domain.model.OrderLine
import com.tinhcd.myesalessfa.domain.model.PricedProduct
import com.tinhcd.myesalessfa.domain.model.ProductSort
import com.tinhcd.myesalessfa.domain.model.SuggestedPart

/**
 * Đặt hàng, laid out the way the app it replaces lays it out.
 *
 * That app splits ordering across three screens and this follows it: the basket
 * is what the step opens on, a + button leads to the catalogue, and a
 * confirmation carries the totals and the note. The single scrolling list this
 * used to be put the catalogue first and the order last, which is backwards for
 * the question a rep is actually asked at the counter — "so what have I got?".
 *
 * Promotions are not calculated. The legacy confirmation carries automatic and
 * manual promotions, rewards and order-level discounts; the totals here are the
 * plain arithmetic of the lines, and nothing on screen claims otherwise.
 */
@Composable
fun TakeOrderScreen(
    onDone: () -> Unit,
    viewModel: TakeOrderViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(state.finished) {
        if (state.finished) onDone()
    }

    // Back inside the step walks back a page. Leaving from the basket is the only
    // way out, so a rep cannot drop out of ordering from the middle of it.
    BackHandler(enabled = state.page != TakeOrderPage.BASKET) { viewModel.openBasket() }

    Scaffold(
        topBar = {
            when (state.page) {
                TakeOrderPage.BASKET -> StepHeader(
                    title = "Giỏ hàng",
                    subtitle = listOfNotNull(
                        state.customerName.ifBlank { null },
                        state.order.lines.size.takeIf { it > 0 }?.let { "$it mặt hàng" },
                    ).joinToString(" - "),
                    onBack = onDone,
                )

                TakeOrderPage.PRODUCTS -> ProductsHeader(state, viewModel)

                TakeOrderPage.CONFIRM -> StepHeader(
                    title = "Xác nhận đơn hàng",
                    subtitle = state.customerName,
                    onBack = viewModel::openBasket,
                )
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding)) {
            when {
                state.loading -> LoadingBox()

                state.catalogue.isEmpty() -> ErrorBox(
                    state.error ?: "Danh mục sản phẩm trống. Đăng nhập lại để tải về.",
                    onRetry = viewModel::load,
                )

                else -> when (state.page) {
                    TakeOrderPage.BASKET -> BasketPage(state, viewModel)
                    TakeOrderPage.PRODUCTS -> ProductsPage(state, viewModel)
                    TakeOrderPage.CONFIRM -> ConfirmPage(state, viewModel, onDone)
                }
            }
        }
    }

    if (state.editing != null) {
        EditLineSheet(state, viewModel)
    }
}

// =============================================================================
// Giỏ hàng
// =============================================================================

/**
 * What has been ordered so far, and the only page that can leave the step.
 *
 * One card per line rather than per product, because a product legitimately holds
 * two — a case and a few loose pieces, which is what the stock-count suggestion
 * writes. A card that summed them would be a card the rep cannot edit back apart.
 */
@Composable
private fun BasketPage(state: TakeOrderUiState, viewModel: TakeOrderViewModel) {
    var pendingDelete by remember { mutableStateOf<OrderLine?>(null) }

    Column(Modifier.fillMaxSize()) {
        // The button floats over the list, not over the screen: anchoring it to the
        // window put it across the total, and a total is the one thing on this page
        // a rep must be able to read.
        Box(Modifier.weight(1f)) {
            if (state.order.lines.isEmpty()) {
                EmptyBasket()
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 88.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(state.basketLines, key = { it.productId + it.uomCode }) { line ->
                        BasketCard(
                            line = line,
                            onEdit = { viewModel.startEdit(line) },
                            onDelete = { pendingDelete = line },
                        )
                    }
                }
            }

            FloatingActionButton(
                onClick = viewModel::openProducts,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp),
            ) { Icon(Icons.Default.Add, contentDescription = "Thêm sản phẩm") }
        }

        BasketFooter(
            state = state,
            onConfirm = viewModel::openConfirm,
        )
    }

    pendingDelete?.let { line ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Bỏ sản phẩm") },
            text = { Text("Bỏ ${line.productName} khỏi giỏ hàng?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.removeLine(line)
                        pendingDelete = null
                    },
                ) { Text("Bỏ") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Đóng") }
            },
        )
    }
}

@Composable
private fun EmptyBasket() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxSize(),
    ) {
        Icon(
            Icons.Default.ShoppingCart,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(48.dp),
        )
        Spacer(Modifier.height(12.dp))
        Text("Giỏ hàng chưa có sản phẩm", style = MaterialTheme.typography.bodyLarge)
        Text(
            "Bấm + để chọn sản phẩm",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun BasketCard(line: OrderLine, onEdit: () -> Unit, onDelete: () -> Unit) {
    Card(
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    text = line.productName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDelete, modifier = Modifier.size(28.dp)) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Bỏ ${line.productName}",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }

            Spacer(Modifier.height(6.dp))
            Text(
                text = "${line.productCode} | ${line.uomName}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = formatDong(line.unitPrice),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = line.qty.toString(),
                    style = MaterialTheme.typography.bodyLarge,
                )
                IconButton(onClick = onEdit, modifier = Modifier.size(28.dp)) {
                    Icon(
                        Icons.Default.Edit,
                        contentDescription = "Sửa ${line.productName}",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp),
                    )
                }
                Spacer(Modifier.weight(1f))
                Text(
                    text = formatDong(line.grossAmount),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

/**
 * The basket's total is before VAT, as the legacy basket's is.
 *
 * Tax belongs on the confirmation, where it is broken out beside the figure it
 * applies to. Showing a VAT-inclusive number here and a different "tạm tính" one
 * page later would read as the price having changed.
 */
@Composable
private fun BasketFooter(state: TakeOrderUiState, onConfirm: () -> Unit) {
    Surface(shadowElevation = 8.dp) {
        Column(
            Modifier
                .navigationBarsPadding()
                .padding(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Tổng tiền",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    formatDong(state.order.subTotal),
                    style = MaterialTheme.typography.titleLarge,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = MoneyGreen,
                )
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
                text = "Xác nhận",
                onClick = onConfirm,
                enabled = state.order.canSubmit,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}

// =============================================================================
// Danh sách sản phẩm
// =============================================================================

@Composable
private fun ProductsHeader(state: TakeOrderUiState, viewModel: TakeOrderViewModel) {
    var sortOpen by remember { mutableStateOf(false) }
    var filterOpen by remember { mutableStateOf(false) }

    StepHeader(
        title = "Danh sách sản phẩm",
        subtitle = state.customerName,
        onBack = viewModel::openBasket,
        actions = {
            // Badged the way the legacy bar badges them, so a rep can see from the
            // list that it is narrowed without opening either sheet to check.
            HeaderAction(
                icon = Icons.Default.SwapVert,
                description = "Sắp xếp",
                badge = if (state.sort == ProductSort.DEFAULT) null else "1",
                onClick = { sortOpen = true },
            )
            HeaderAction(
                icon = Icons.Default.FilterList,
                description = "Lọc nhóm hàng",
                badge = state.categories.size.takeIf { it > 0 }?.toString(),
                onClick = { filterOpen = true },
            )
        },
        below = {
            OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::onQueryChange,
                placeholder = { Text("Tìm tên hoặc mã sản phẩm") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                colors = searchFieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )
        },
    )

    if (sortOpen) {
        SortSheet(
            selected = state.sort,
            onSelect = {
                viewModel.onSortChange(it)
                sortOpen = false
            },
            onDismiss = { sortOpen = false },
        )
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
}

@Composable
private fun HeaderAction(
    icon: ImageVector,
    description: String,
    badge: String?,
    onClick: () -> Unit,
) {
    val tint = MaterialTheme.brand.onHeader
    IconButton(onClick = onClick) {
        BadgedBox(
            badge = { if (badge != null) Badge { Text(badge) } },
        ) {
            Icon(icon, contentDescription = description, tint = tint)
        }
    }
}

/**
 * White field on the blue band, the way the route header's search box sits on it.
 * Left to the defaults it would inherit outline and label colours meant for the
 * page background and all but disappear.
 */
@Composable
private fun searchFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = MaterialTheme.colorScheme.surface,
    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
    focusedBorderColor = Color.Transparent,
    unfocusedBorderColor = Color.Transparent,
)

/**
 * The catalogue, with a quantity field on every row.
 *
 * Nothing here touches the basket as it is typed. The rep walks the shelf filling
 * rows in and commits them together, which is how the legacy list works and why
 * its button says "cập nhật giỏ hàng" rather than "thêm".
 */
@Composable
private fun ProductsPage(state: TakeOrderUiState, viewModel: TakeOrderViewModel) {
    Column(
        Modifier
            .fillMaxSize()
            .imePadding(),
    ) {
        SuggestionBanner(state = state, onApply = viewModel::applySuggestions)

        val visible = state.visible
        if (visible.isEmpty()) {
            Box(Modifier.weight(1f)) { ErrorBox("Không tìm thấy sản phẩm nào") }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(visible, key = { it.product.id }) { product ->
                    ProductRow(
                        product = product,
                        state = state,
                        onQtyChange = { viewModel.onDraftQtyChange(product, it) },
                    )
                }
            }
        }

        ProductsFooter(state = state, onCommit = viewModel::commitDraft)
    }
}

@Composable
private fun ProductRow(
    product: PricedProduct,
    state: TakeOrderUiState,
    onQtyChange: (Int) -> Unit,
) {
    val unit = state.unitFor(product)
    val qty = state.listQtyOf(product)
    val available = state.availableIn(product)

    Card(
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                text = product.product.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            Spacer(Modifier.height(6.dp))
            Text(
                text = buildString {
                    append(product.product.code)
                    append(" | ")
                    append(unit.unit.uomName)
                    // The legacy row prints the warehouse's figure here, in the
                    // unit the row is priced in. Absent rather than zero when the
                    // warehouse could not be read: "0" would read as sold out.
                    if (available != null) append(" (Tồn kho: $available)")
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(6.dp))
            Text(
                text = formatDong(unit.price),
                style = MaterialTheme.typography.bodyMedium,
            )

            val suggestion = state.suggestionFor(product.product.id)
            if (suggestion != null) {
                Text(
                    text = "Thiếu ${suggestion.shortfallBaseQty} " +
                        "${product.product.baseUomCode.lowercase()} so với định mức " +
                        "- gợi ý ${suggestion.parts.describe()}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }

            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (qty > 0) formatDong(qty * unit.price) else "",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                )

                OutlinedTextField(
                    value = if (qty == 0) "" else qty.toString(),
                    onValueChange = { typed ->
                        // Ignore anything that is not a number rather than clearing
                        // the field: a fat-fingered letter should not lose the qty.
                        onQtyChange(typed.filter { it.isDigit() }.take(5).toIntOrNull() ?: 0)
                    },
                    placeholder = { Text("0", textAlign = TextAlign.End, modifier = Modifier.fillMaxWidth()) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    shape = RoundedCornerShape(8.dp),
                    textStyle = LocalTextStyle.current.copy(textAlign = TextAlign.End),
                    modifier = Modifier.width(104.dp),
                )
            }
        }
    }
}

@Composable
private fun ProductsFooter(state: TakeOrderUiState, onCommit: () -> Unit) {
    Surface(shadowElevation = 8.dp) {
        Column(
            Modifier
                .navigationBarsPadding()
                .padding(16.dp),
        ) {
            if (state.error != null) {
                Text(
                    state.error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            PrimaryButton(
                text = "Cập nhật giỏ hàng",
                onClick = onCommit,
                enabled = state.hasDraft,
            )
        }
    }
}

/**
 * Offers the replenishment the stock count implies, rather than applying it.
 *
 * An order is a commitment to the customer. A screen that opens already filled in
 * invites submitting quantities nobody agreed to, so the rep presses this.
 */
@Composable
private fun SuggestionBanner(state: TakeOrderUiState, onApply: () -> Unit) {
    if (state.suggestions.isEmpty()) return

    // Counted in lines, not in "units". Adding a case to three loose pieces and
    // calling the answer four of anything was meaningless even before a suggestion
    // could span two units; now it would be actively wrong.
    val totalLines = state.suggestions.sumOf { it.parts.size }

    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "Gợi ý từ kiểm tồn: ${state.suggestions.size} mặt hàng dưới định mức",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    if (state.suggestionsApplied) {
                        "Đã thêm $totalLines dòng vào giỏ - sửa lại trước khi gửi nếu cần"
                    } else {
                        "Sẽ thêm $totalLines dòng"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }

            if (!state.suggestionsApplied) {
                OutlinedButton(onClick = onApply) { Text("Áp dụng") }
            }
        }
    }
}

// =============================================================================
// Xác nhận đơn hàng
// =============================================================================

@Composable
private fun ConfirmPage(
    state: TakeOrderUiState,
    viewModel: TakeOrderViewModel,
    onDone: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .imePadding(),
    ) {
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { OrderSummaryCard(state, viewModel) }

            item {
                Text(
                    "Sản phẩm",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                )
            }

            items(state.basketLines, key = { it.productId + it.uomCode }) { line ->
                ConfirmLineRow(line)
            }
        }

        Surface(shadowElevation = 8.dp) {
            Column(
                Modifier
                    .navigationBarsPadding()
                    .padding(16.dp),
            ) {
                if (state.error != null) {
                    Text(
                        state.error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                PrimaryButton(
                    text = "Gửi đơn hàng",
                    onClick = viewModel::submit,
                    enabled = state.order.canSubmit,
                    loading = state.submitting,
                )
                OutlinedButton(
                    onClick = onDone,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                ) { Text("Để sau") }
            }
        }
    }
}

@Composable
private fun OrderSummaryCard(state: TakeOrderUiState, viewModel: TakeOrderViewModel) {
    Card(
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            AmountRow("Tổng số mặt hàng", state.order.lines.size.toString())
            AmountRow("Tổng số lượng", state.order.totalQty.toString())
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            AmountRow("Tiền hàng (chưa VAT)", formatDong(state.order.subTotal))
            AmountRow("Thuế VAT", formatDong(state.order.vatAmount))
            Spacer(Modifier.height(8.dp))
            AmountRow(
                label = "Tổng cộng",
                value = formatDong(state.order.totalAmount),
                emphasise = true,
            )

            OutlinedTextField(
                value = state.order.note,
                onValueChange = viewModel::onNoteChange,
                placeholder = { Text("Ghi chú đơn hàng") },
                shape = RoundedCornerShape(12.dp),
                minLines = 2,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
            )
        }
    }
}

@Composable
private fun ConfirmLineRow(line: OrderLine) {
    Column {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(
                    line.productName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    "${line.productCode} | ${line.qty} ${line.uomName} x " +
                        formatDong(line.unitPrice),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                formatDong(line.grossAmount),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
        }
        HorizontalDivider(Modifier.padding(top = 8.dp))
    }
}

// =============================================================================
// The edit sheet — the legacy "thêm vào giỏ" dialog, opened from a basket card
// =============================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditLineSheet(state: TakeOrderUiState, viewModel: TakeOrderViewModel) {
    val editing = state.editing ?: return
    val product = state.product(editing.productId) ?: return
    val unit = product.units.firstOrNull { it.unit.uomCode == editing.uomCode }
        ?: product.defaultUnit

    ModalBottomSheet(
        onDismissRequest = viewModel::cancelEdit,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 16.dp),
        ) {
            Text(
                product.product.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                product.product.code,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider(Modifier.padding(vertical = 12.dp))

            Text("Đơn vị tính", style = MaterialTheme.typography.bodyLarge)
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 8.dp),
            ) {
                product.units.forEach { option ->
                    FilterChip(
                        selected = option.unit.uomCode == editing.uomCode,
                        onClick = { viewModel.onEditUnitChange(option.unit.uomCode) },
                        label = { Text(option.unit.uomName) },
                    )
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 12.dp))

            // Read-only. The legacy sheet lets a rep retype the price when head
            // office turns on `isEnableEditPrice`; no such switch exists here, and
            // an editable field the server would overwrite is worse than none.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Đơn giá", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Text(formatDong(unit.price), style = MaterialTheme.typography.bodyLarge)
            }
            if (unit.unit.conversionRate > 1) {
                Text(
                    "1 ${unit.unit.uomName} = ${unit.unit.conversionRate} " +
                        product.product.baseUomCode.lowercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            HorizontalDivider(Modifier.padding(vertical = 12.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Số lượng", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                OutlinedTextField(
                    value = if (editing.qty == 0) "" else editing.qty.toString(),
                    onValueChange = { typed ->
                        viewModel.onEditQtyChange(
                            typed.filter { it.isDigit() }.take(5).toIntOrNull() ?: 0,
                        )
                    },
                    placeholder = { Text("0", textAlign = TextAlign.End, modifier = Modifier.fillMaxWidth()) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    shape = RoundedCornerShape(8.dp),
                    textStyle = LocalTextStyle.current.copy(textAlign = TextAlign.End),
                    modifier = Modifier.width(120.dp),
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 12.dp)) {
                Text(
                    "Thành tiền",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    formatDong(editing.qty * unit.price),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MoneyGreen,
                )
            }

            PrimaryButton(
                text = "Cập nhật",
                onClick = viewModel::applyEdit,
                enabled = editing.qty > 0,
                modifier = Modifier.padding(top = 16.dp),
            )
        }
    }
}

// =============================================================================
// Sort and filter sheets
// =============================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SortSheet(
    selected: ProductSort,
    onSelect: (ProductSort) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(Modifier.navigationBarsPadding()) {
            Text(
                "Sắp xếp theo",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 12.dp),
            )
            HorizontalDivider()
            ProductSort.entries.forEach { option ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        // The whole row, not just the button. A 20dp target in a
                        // sheet meant to be used one-handed is a row that looks
                        // broken when the label is tapped and nothing happens.
                        .clickable { onSelect(option) }
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                ) {
                    RadioButton(
                        selected = option == selected,
                        onClick = { onSelect(option) },
                    )
                    Text(option.label, style = MaterialTheme.typography.bodyLarge)
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CategorySheet(
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

// =============================================================================
// Small shared pieces
// =============================================================================

@Composable
private fun AmountRow(label: String, value: String, emphasise: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
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
            fontSize = if (emphasise) 18.sp else MaterialTheme.typography.bodyLarge.fontSize,
            fontWeight = if (emphasise) FontWeight.Bold else FontWeight.Normal,
            color = if (emphasise) MoneyGreen else MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * "1 thung + 2 chai" — a suggestion spanning several sale units, read in the order
 * the rep would pick it: biggest first.
 */
private fun List<SuggestedPart>.describe(): String =
    joinToString(" + ") { "${it.qty} ${it.uomName}" }

/** The green the legacy screens print money in. */
private val MoneyGreen = Color(0xFF04A489)
