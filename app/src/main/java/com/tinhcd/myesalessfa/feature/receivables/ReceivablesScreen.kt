package com.tinhcd.myesalessfa.feature.receivables

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tinhcd.myesalessfa.core.ui.ErrorBox
import com.tinhcd.myesalessfa.core.ui.LoadingBox
import com.tinhcd.myesalessfa.core.ui.PrimaryButton
import com.tinhcd.myesalessfa.core.ui.theme.MyeSalesTheme
import com.tinhcd.myesalessfa.core.ui.theme.brand
import com.tinhcd.myesalessfa.domain.model.PaymentDraft
import com.tinhcd.myesalessfa.domain.model.ReceivableCustomer
import com.tinhcd.myesalessfa.domain.model.ReceivableInvoice
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * What the outlets owe, and the money collected against it.
 *
 * Two levels in one screen: the list of debtors, and one outlet's invoices with
 * an amount box on each. The detail is a full screen rather than a sheet because
 * the rep is doing arithmetic in it with a person waiting, and a sheet that
 * dismisses on a stray swipe would lose the figures they had already typed.
 */
@Composable
fun ReceivablesScreen(
    onBack: () -> Unit,
    viewModel: ReceivablesViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    val open = state.open
    if (open != null) {
        CustomerDetail(
            open = open,
            onBack = viewModel::closeCustomer,
            onAmountChanged = viewModel::onAmountChanged,
            onPayInFull = viewModel::payInFull,
            onNoteChanged = viewModel::onNoteChanged,
            onSubmit = viewModel::submit,
        )
    } else {
        CustomerList(
            state = state,
            onBack = onBack,
            onQueryChanged = viewModel::onQueryChanged,
            onOpen = viewModel::openCustomer,
            onRetry = viewModel::load,
        )
    }
}

// -----------------------------------------------------------------------------
// List
// -----------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CustomerList(
    state: ReceivablesUiState,
    onBack: () -> Unit,
    onQueryChanged: (String) -> Unit,
    onOpen: (ReceivableCustomer) -> Unit,
    onRetry: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Công nợ") },
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
                state.customers.isEmpty() -> ErrorBox("Không có khách hàng nào còn nợ")
                else -> LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item { TotalCard(state = state) }
                    item {
                        OutlinedTextField(
                            value = state.query,
                            onValueChange = onQueryChanged,
                            label = { Text("Tìm khách hàng") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    if (state.visible.isEmpty()) {
                        item {
                            Text(
                                text = "Không tìm thấy khách hàng phù hợp",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                            )
                        }
                    }

                    items(state.visible, key = { it.customerId }) { customer ->
                        DebtorCard(customer = customer, onClick = { onOpen(customer) })
                    }
                }
            }
        }
    }
}

@Composable
private fun TotalCard(state: ReceivablesUiState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = "Tổng công nợ (VND)",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = formatMoney(state.totalOutstanding),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "${state.customers.size} khách hàng · ${state.overdueCount} quá hạn",
                style = MaterialTheme.typography.bodySmall,
                color = if (state.overdueCount > 0) OverdueRed else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DebtorCard(customer: ReceivableCustomer, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = customer.customerName,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = listOfNotNull(customer.customerCode, customer.address)
                        .joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${customer.invoices} hoá đơn",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = formatMoney(customer.outstanding),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                )
                if (customer.overdue) {
                    Surface(
                        color = OverdueRed,
                        contentColor = Color.White,
                        shape = CircleShape,
                        modifier = Modifier.padding(top = 4.dp),
                    ) {
                        Text(
                            text = "Quá hạn",
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }
                }
            }
        }
    }
}

// -----------------------------------------------------------------------------
// Detail
// -----------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CustomerDetail(
    open: OpenCustomer,
    onBack: () -> Unit,
    onAmountChanged: (String, Long) -> Unit,
    onPayInFull: (ReceivableInvoice) -> Unit,
    onNoteChanged: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    val today = LocalDate.now()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(open.customer.customerName, maxLines = 1)
                        Text(
                            text = open.customer.customerCode,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                },
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
        bottomBar = {
            Surface(shadowElevation = 8.dp) {
                Column(Modifier.padding(16.dp)) {
                    Row(Modifier.fillMaxWidth()) {
                        Text(
                            text = "Tổng thu",
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = formatMoney(open.draft.total),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    PrimaryButton(
                        text = "Ghi nhận thu tiền",
                        onClick = onSubmit,
                        enabled = open.canSubmit,
                        loading = open.submitting,
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (open.loading) {
                LoadingBox()
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    open.error?.let { message -> item { Notice(message) } }

                    if (open.collected) {
                        item { Notice(message = "Đã ghi nhận số tiền thu.", error = false) }
                    }

                    if (open.invoices.isEmpty()) {
                        item {
                            Text(
                                text = "Khách hàng này không còn hoá đơn nào chưa thanh toán",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                            )
                        }
                    }

                    items(open.invoices, key = { it.invoiceId }) { invoice ->
                        InvoiceCard(
                            invoice = invoice,
                            today = today,
                            amount = open.draft.amounts[invoice.invoiceId] ?: 0,
                            overrun = invoice.invoiceId in open.overrun,
                            onAmountChanged = { onAmountChanged(invoice.invoiceId, it) },
                            onPayInFull = { onPayInFull(invoice) },
                        )
                    }

                    item {
                        OutlinedTextField(
                            value = open.draft.note,
                            onValueChange = onNoteChanged,
                            label = { Text("Ghi chú phiếu thu") },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun InvoiceCard(
    invoice: ReceivableInvoice,
    today: LocalDate,
    amount: Long,
    overrun: Boolean,
    onAmountChanged: (Long) -> Unit,
    onPayInFull: () -> Unit,
) {
    val late = invoice.isOverdue(today)

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = invoice.invoiceNo,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = "Ngày ${DateFormat.format(invoice.issuedOn)} · " +
                            "hạn ${DateFormat.format(invoice.dueOn)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (late) OverdueRed else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (late) {
                        Text(
                            text = "Quá hạn ${invoice.daysLate(today)} ngày",
                            style = MaterialTheme.typography.labelSmall,
                            color = OverdueRed,
                        )
                    }
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = formatMoney(invoice.outstanding),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    if (invoice.paid > 0) {
                        Text(
                            text = "đã trả ${formatMoney(invoice.paid)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 10.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = if (amount == 0L) "" else amount.toString(),
                    onValueChange = { text ->
                        onAmountChanged(text.filter { it.isDigit() }.toLongOrNull() ?: 0L)
                    },
                    label = { Text("Số tiền thu") },
                    singleLine = true,
                    isError = overrun,
                    supportingText = {
                        if (overrun) Text("Vượt quá dư nợ của hoá đơn này")
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = onPayInFull) { Text("Thu hết") }
            }
        }
    }
}

@Composable
private fun Notice(message: String, error: Boolean = true) {
    Surface(
        color = if (error) {
            MaterialTheme.colorScheme.errorContainer
        } else {
            MaterialTheme.colorScheme.primaryContainer
        },
        contentColor = if (error) {
            MaterialTheme.colorScheme.onErrorContainer
        } else {
            MaterialTheme.colorScheme.onPrimaryContainer
        },
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(12.dp),
        )
    }
}

private val OverdueRed = Color(0xFFD5262B)

private val DateFormat = DateTimeFormatter.ofPattern("dd/MM/yyyy")

private val MoneyFormat = DecimalFormat(
    "#,##0",
    DecimalFormatSymbols(Locale.US).apply { groupingSeparator = '.' },
)

private fun formatMoney(value: Long): String = MoneyFormat.format(value)

// -----------------------------------------------------------------------------
// Previews
// -----------------------------------------------------------------------------

private val SampleCustomers = listOf(
    ReceivableCustomer(
        customerId = "c1",
        customerCode = "KH0012",
        customerName = "Tạp hoá Bà Bảy",
        phone = "0901234567",
        address = "12 Nguyễn Văn Cừ",
        invoices = 3,
        outstanding = 12_400_000,
        overdue = true,
    ),
    ReceivableCustomer(
        customerId = "c2",
        customerCode = "KH0013",
        customerName = "Cửa hàng Minh Anh",
        phone = null,
        address = "45 Lý Thường Kiệt",
        invoices = 1,
        outstanding = 3_100_000,
        overdue = false,
    ),
)

private val SampleInvoices = listOf(
    ReceivableInvoice(
        invoiceId = "i1",
        invoiceNo = "HD00123",
        issuedOn = LocalDate.of(2026, 6, 20),
        dueOn = LocalDate.of(2026, 7, 20),
        total = 8_000_000,
        paid = 2_000_000,
        outstanding = 6_000_000,
        note = null,
    ),
    ReceivableInvoice(
        invoiceId = "i2",
        invoiceNo = "HD00147",
        issuedOn = LocalDate.of(2026, 8, 1),
        dueOn = LocalDate.of(2026, 9, 1),
        total = 6_400_000,
        paid = 0,
        outstanding = 6_400_000,
        note = null,
    ),
)

@Preview(name = "Công nợ - danh sách", showBackground = true, heightDp = 800)
@Composable
private fun ReceivableListPreview() {
    MyeSalesTheme {
        CustomerList(
            state = ReceivablesUiState(loading = false, customers = SampleCustomers),
            onBack = {},
            onQueryChanged = {},
            onOpen = {},
            onRetry = {},
        )
    }
}

@Preview(name = "Công nợ - thu tiền", showBackground = true, heightDp = 800)
@Composable
private fun ReceivableDetailPreview() {
    MyeSalesTheme {
        CustomerDetail(
            open = OpenCustomer(
                customer = SampleCustomers.first(),
                invoices = SampleInvoices,
                draft = PaymentDraft(
                    customerId = "c1",
                    batchId = "batch",
                    amounts = mapOf("i1" to 6_000_000L),
                ),
                loading = false,
            ),
            onBack = {},
            onAmountChanged = { _, _ -> },
            onPayInFull = {},
            onNoteChanged = {},
            onSubmit = {},
        )
    }
}
