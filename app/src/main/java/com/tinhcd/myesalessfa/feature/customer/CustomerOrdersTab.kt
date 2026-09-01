package com.tinhcd.myesalessfa.feature.customer

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.tinhcd.myesalessfa.core.ui.ErrorBox
import com.tinhcd.myesalessfa.core.ui.LoadingBox
import com.tinhcd.myesalessfa.core.ui.formatDong
import com.tinhcd.myesalessfa.core.ui.theme.MyeSalesTheme
import com.tinhcd.myesalessfa.domain.model.CustomerOrder
import com.tinhcd.myesalessfa.domain.model.CustomerOrderLine
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val OrderDate = DateTimeFormatter.ofPattern("dd/MM/yyyy")

/**
 * Lịch sử đơn hàng — what this outlet has taken before.
 *
 * The lines are collapsed behind the header because of what a rep is usually
 * doing here: answering "how much did I take last time", which the total on the
 * row already answers. The lines matter for the follow-up question — what was in
 * it — and that one is asked about a single order, not all of them.
 *
 * Shows this rep's orders only, because that is what the server returns and what
 * they can stand behind. A total including a colleague's orders is a number the
 * person in the doorway cannot explain.
 */
@Composable
fun CustomerOrdersTab(
    state: CustomerOrdersUiState,
    onRetry: () -> Unit,
    onToggleOrder: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize()) {
        when {
            state.loading -> LoadingBox()
            state.error != null -> ErrorBox(state.error, onRetry = onRetry)
            state.orders.isEmpty() -> EmptyOrders()
            else -> LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(state.orders, key = { it.orderId }) { order ->
                    OrderCard(
                        order = order,
                        expanded = state.expandedOrderId == order.orderId,
                        onToggle = { onToggleOrder(order.orderId) },
                    )
                }
            }
        }
    }
}

/**
 * Not an error state. A shop this rep has never sold to is an ordinary thing —
 * a new outlet, or one another rep used to cover — and the retry button an
 * ErrorBox would put here invites tapping at a server that answered correctly.
 */
@Composable
private fun EmptyOrders() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Chưa có đơn hàng nào",
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "Bạn chưa đặt đơn nào cho khách hàng này",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun OrderCard(order: CustomerOrder, expanded: Boolean, onToggle: () -> Unit) {
    Card(
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = order.orderNo,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = order.orderDate.format(OrderDate),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                StatusChip(order.status)

                Spacer(Modifier.width(8.dp))

                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "Thu gọn" else "Xem chi tiết",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }

            Spacer(Modifier.height(10.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "${order.skuCount} mặt hàng · ${order.totalQty} đơn vị",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = formatDong(order.totalAmount),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column {
                    Spacer(Modifier.height(10.dp))
                    HorizontalDivider()
                    order.lines.forEach { line ->
                        OrderLineRow(line)
                    }
                }
            }
        }
    }
}

@Composable
private fun OrderLineRow(line: CustomerOrderLine) {
    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = line.productName,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = "${line.productCode} · ${line.qty} ${line.uomCode}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = formatDong(line.lineAmount),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/**
 * The rep's own orders arrive `new` until the ERP acknowledges them, so most
 * rows say "Đã gửi" rather than confirmed. Worth distinguishing: a shop chasing
 * a delivery is asking about exactly that difference.
 */
@Composable
private fun StatusChip(status: String) {
    val (label, color) = when (status) {
        "confirmed" -> "Đã xác nhận" to MaterialTheme.colorScheme.primary
        "new" -> "Đã gửi" to MaterialTheme.colorScheme.tertiary
        else -> status to MaterialTheme.colorScheme.outline
    }

    Surface(
        color = color,
        contentColor = Color.White,
        shape = RoundedCornerShape(12.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

private val sampleOrders = listOf(
    CustomerOrder(
        orderId = "o1",
        orderNo = "DH2609010001",
        orderDate = LocalDate.of(2026, 8, 28),
        status = "confirmed",
        totalAmount = 2_508_000,
        lines = listOf(
            CustomerOrderLine("SP001", "Nước ngọt Cola 330ml", "CASE", 8, 1_824_000),
            CustomerOrderLine("SP004", "Nước suối 500ml", "CASE", 4, 684_000),
        ),
    ),
    CustomerOrder(
        orderId = "o2",
        orderNo = "DH2608200007",
        orderDate = LocalDate.of(2026, 8, 20),
        status = "new",
        totalAmount = 912_000,
        lines = listOf(
            CustomerOrderLine("SP001", "Nước ngọt Cola 330ml", "CASE", 4, 912_000),
        ),
    ),
)

@Preview(showBackground = true)
@Composable
private fun CustomerOrdersTabPreview() {
    MyeSalesTheme {
        CustomerOrdersTab(
            state = CustomerOrdersUiState(
                loading = false,
                orders = sampleOrders,
                expandedOrderId = "o1",
            ),
            onRetry = {},
            onToggleOrder = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun CustomerOrdersEmptyPreview() {
    MyeSalesTheme {
        CustomerOrdersTab(
            state = CustomerOrdersUiState(loading = false),
            onRetry = {},
            onToggleOrder = {},
        )
    }
}
