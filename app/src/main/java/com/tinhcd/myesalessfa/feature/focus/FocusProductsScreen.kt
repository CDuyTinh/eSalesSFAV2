package com.tinhcd.myesalessfa.feature.focus

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tinhcd.myesalessfa.core.ui.ErrorBox
import com.tinhcd.myesalessfa.core.ui.LoadingBox
import com.tinhcd.myesalessfa.core.ui.theme.MyeSalesTheme
import com.tinhcd.myesalessfa.core.ui.theme.brand
import com.tinhcd.myesalessfa.domain.model.FocusProduct
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * What head office wants pushed, and how the rep is doing on it.
 *
 * A briefing, read before the round. Nothing here is editable: the list is head
 * office's instruction and the figures are the rep's own orders — neither is
 * something to type into.
 */
@Composable
fun FocusProductsScreen(
    onBack: () -> Unit,
    viewModel: FocusProductsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    FocusProductsContent(state = state, onBack = onBack, onRetry = viewModel::load)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FocusProductsContent(
    state: FocusProductsUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sản phẩm trọng tâm") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Quay lại",
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.brand.header,
                    titleContentColor = MaterialTheme.brand.onHeader,
                    navigationIconContentColor = MaterialTheme.brand.onHeader,
                ),
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                state.loading -> LoadingBox()
                state.error != null -> ErrorBox(state.error, onRetry = onRetry)
                state.products.isEmpty() ->
                    ErrorBox("Hiện không có sản phẩm trọng tâm nào đang chạy")

                else -> LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (state.measuredCount > 0) {
                        item {
                            Text(
                                text = "Đạt chỉ tiêu ${state.onTrack}/${state.measuredCount} " +
                                    "sản phẩm có định mức",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    items(state.products, key = { it.focusId }) { product ->
                        FocusCard(product = product, today = state.date)
                    }
                }
            }
        }
    }
}

@Composable
private fun FocusCard(product: FocusProduct, today: LocalDate) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = product.productName,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = product.productCode,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                product.percent?.let { percent ->
                    Spacer(Modifier.width(8.dp))
                    PercentPill(percent = percent)
                }
            }

            // The instruction, when there is one. Head office writes it to be said
            // at the counter, so it sits above the numbers rather than under them.
            product.note?.takeIf { it.isNotBlank() }?.let { note ->
                Spacer(Modifier.height(8.dp))
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Text(
                        text = note,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(10.dp),
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            val target = product.targetBaseQty
            Text(
                text = if (target != null) {
                    "Đã bán ${product.soldBaseQty}/${target} ${product.baseUom}"
                } else {
                    "Đã bán ${product.soldBaseQty} ${product.baseUom}"
                },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                // Coverage next to quantity, because one large order can meet a
                // target while the product reaches almost no shelves.
                text = "${product.outlets} điểm bán đã lấy hàng",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            product.progress?.let { progress ->
                Spacer(Modifier.height(8.dp))
                ProgressBar(progress = progress)
                product.remaining()?.takeIf { it > 0 }?.let { left ->
                    Text(
                        text = "Còn thiếu $left ${product.baseUom}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            val daysLeft = product.daysLeft(today)
            Text(
                text = "Đến ${DateFormat.format(product.toDate)} · còn $daysLeft ngày",
                style = MaterialTheme.typography.labelSmall,
                color = if (product.isEndingSoon(today)) {
                    EndingAmber
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

@Composable
private fun PercentPill(percent: Int) {
    val colour = if (percent >= 100) MetGreen else MaterialTheme.colorScheme.primary
    Surface(
        color = colour.copy(alpha = 0.14f),
        contentColor = colour,
        shape = CircleShape,
    ) {
        Text(
            text = "$percent%",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun ProgressBar(progress: Float) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(8.dp)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f), CircleShape),
    ) {
        Box(
            Modifier
                .fillMaxWidth(progress)
                .height(8.dp)
                .background(Brush.horizontalGradient(FocusRamp), CircleShape),
        )
    }
}

private val FocusRamp = listOf(Color(0xFF04A489), Color(0xFF36B6A0))
private val MetGreen = Color(0xFF04A489)
private val EndingAmber = Color(0xFFF5A202)

private val DateFormat = DateTimeFormatter.ofPattern("dd/MM/yyyy")

// -----------------------------------------------------------------------------
// Previews
// -----------------------------------------------------------------------------

private val SampleProducts = listOf(
    FocusProduct(
        focusId = "f1",
        productId = "p1",
        productCode = "NGK001",
        productName = "Nước ngọt Coca-Cola 330ml",
        baseUom = "PCS",
        fromDate = LocalDate.of(2026, 8, 1),
        toDate = LocalDate.of(2026, 8, 31),
        priority = 1,
        targetBaseQty = 500,
        note = "Ưu tiên trưng bày ngang tầm mắt, nhắc khách về giá khuyến mãi tháng 8.",
        soldBaseQty = 380,
        outlets = 6,
    ),
    FocusProduct(
        focusId = "f2",
        productId = "p2",
        productCode = "NGK003",
        productName = "Nước suối Aquafina 500ml",
        baseUom = "PCS",
        fromDate = LocalDate.of(2026, 8, 15),
        toDate = LocalDate.of(2026, 8, 20),
        priority = 2,
        // Qualitative: get it on the shelf. Not the same as a target of zero.
        targetBaseQty = null,
        note = null,
        soldBaseQty = 26,
        outlets = 2,
    ),
)

@Preview(name = "Sản phẩm trọng tâm", showBackground = true, heightDp = 800)
@Composable
private fun FocusPreview() {
    MyeSalesTheme {
        FocusProductsContent(
            state = FocusProductsUiState(
                loading = false,
                date = LocalDate.of(2026, 8, 18),
                products = SampleProducts,
            ),
            onBack = {},
            onRetry = {},
        )
    }
}
