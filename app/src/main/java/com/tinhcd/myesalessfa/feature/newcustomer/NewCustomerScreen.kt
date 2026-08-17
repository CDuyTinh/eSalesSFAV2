package com.tinhcd.myesalessfa.feature.newcustomer

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tinhcd.myesalessfa.core.ui.LoadingBox
import com.tinhcd.myesalessfa.core.ui.PrimaryButton
import com.tinhcd.myesalessfa.core.ui.theme.MyeSalesTheme
import com.tinhcd.myesalessfa.core.ui.theme.brand
import com.tinhcd.myesalessfa.domain.model.CustomerOptions
import com.tinhcd.myesalessfa.domain.model.NamedRef
import com.tinhcd.myesalessfa.domain.model.RegisteredCustomer

/**
 * Registering an outlet met in the field.
 *
 * Two required fields at the top and everything else optional below them, in that
 * order deliberately: a rep with a queue behind them can fill the first card and
 * submit, and head office can chase the segment afterwards. Refusing the
 * registration for want of a shop type would lose the outlet altogether.
 */
@Composable
fun NewCustomerScreen(
    onDone: () -> Unit,
    viewModel: NewCustomerViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    NewCustomerContent(
        state = state,
        onBack = onDone,
        onDoneRegistering = onDone,
        onName = viewModel::onName,
        onPhone = viewModel::onPhone,
        onAddress = viewModel::onAddress,
        onNote = viewModel::onNote,
        onProvince = viewModel::onProvince,
        onDistrict = viewModel::onDistrict,
        onWard = viewModel::onWard,
        onClass = viewModel::onClass,
        onChannel = viewModel::onChannel,
        onShopType = viewModel::onShopType,
        onRefreshLocation = viewModel::captureLocation,
        onSubmit = viewModel::submit,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NewCustomerContent(
    state: NewCustomerUiState,
    onBack: () -> Unit,
    onDoneRegistering: () -> Unit,
    onName: (String) -> Unit,
    onPhone: (String) -> Unit,
    onAddress: (String) -> Unit,
    onNote: (String) -> Unit,
    onProvince: (NamedRef?) -> Unit,
    onDistrict: (NamedRef?) -> Unit,
    onWard: (NamedRef?) -> Unit,
    onClass: (NamedRef?) -> Unit,
    onChannel: (NamedRef?) -> Unit,
    onShopType: (NamedRef?) -> Unit,
    onRefreshLocation: () -> Unit,
    onSubmit: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Khách hàng mới") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.brand.header,
                    titleContentColor = MaterialTheme.brand.onHeader,
                ),
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (state.loading) {
                LoadingBox()
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item {
                        SectionTitle("Thông tin bắt buộc")
                    }
                    item {
                        OutlinedTextField(
                            value = state.draft.name,
                            onValueChange = onName,
                            label = { Text("Tên cửa hàng") },
                            singleLine = true,
                            isError = state.showErrors && state.draft.nameError != null,
                            supportingText = {
                                state.draft.nameError
                                    ?.takeIf { state.showErrors }
                                    ?.let { Text(it) }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    item {
                        OutlinedTextField(
                            value = state.draft.address,
                            onValueChange = onAddress,
                            label = { Text("Địa chỉ") },
                            isError = state.showErrors && state.draft.addressError != null,
                            supportingText = {
                                state.draft.addressError
                                    ?.takeIf { state.showErrors }
                                    ?.let { Text(it) }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    item { PositionLine(state = state, onRefresh = onRefreshLocation) }

                    item { SectionTitle("Thông tin thêm") }
                    item {
                        OutlinedTextField(
                            value = state.draft.phone,
                            onValueChange = onPhone,
                            label = { Text("Số điện thoại") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    item {
                        Picker(
                            label = "Tỉnh / Thành phố",
                            options = state.options.provinces,
                            selectedId = state.draft.provinceId,
                            onSelect = onProvince,
                        )
                    }
                    item {
                        Picker(
                            label = "Quận / Huyện",
                            options = state.districts,
                            selectedId = state.draft.districtId,
                            onSelect = onDistrict,
                            emptyHint = "Chọn tỉnh trước",
                        )
                    }
                    item {
                        Picker(
                            label = "Phường / Xã",
                            options = state.wards,
                            selectedId = state.draft.wardId,
                            onSelect = onWard,
                            emptyHint = "Chọn quận trước",
                        )
                    }
                    item {
                        Picker(
                            label = "Nhóm khách hàng",
                            options = state.options.classes,
                            selectedId = state.draft.classId,
                            onSelect = onClass,
                        )
                    }
                    item {
                        Picker(
                            label = "Kênh bán",
                            options = state.options.channels,
                            selectedId = state.draft.channelId,
                            onSelect = onChannel,
                        )
                    }
                    item {
                        Picker(
                            label = "Loại cửa hàng",
                            options = state.options.shopTypes,
                            selectedId = state.draft.shopTypeId,
                            onSelect = onShopType,
                        )
                    }
                    item {
                        OutlinedTextField(
                            value = state.draft.note,
                            onValueChange = onNote,
                            label = { Text("Ghi chú cho văn phòng") },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    state.error?.let { message ->
                        item { Notice(message) }
                    }

                    item {
                        PrimaryButton(
                            text = "Gửi đăng ký",
                            onClick = onSubmit,
                            loading = state.submitting,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                    item {
                        OutlinedButton(
                            onClick = onBack,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Quay lại") }
                    }
                }
            }
        }
    }

    // Named rather than a toast: the code is the thing the rep will be asked for
    // when they ring the office about it, and a toast is gone before they can
    // write it down.
    state.registered?.let { customer ->
        RegisteredDialog(customer = customer, onDismiss = onDoneRegistering)
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 4.dp),
    )
}

/**
 * Says whether a position was captured, and lets the rep take it again.
 *
 * Not a blocker. A shop inside a market hall may never get a fix, and losing the
 * registration over it would be the worse trade — so this states the situation
 * and leaves the decision with the rep.
 */
@Composable
private fun PositionLine(state: NewCustomerUiState, onRefresh: () -> Unit) {
    val point = state.draft.point
    val scheme = MaterialTheme.colorScheme
    val (text, color) = when {
        state.locating -> "Đang lấy vị trí..." to scheme.onSurfaceVariant
        point != null -> "Đã ghi vị trí cửa hàng" to scheme.primary
        else -> "Chưa lấy được vị trí — vẫn gửi đăng ký được" to scheme.secondary
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            Icons.Default.LocationOn,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = color,
            modifier = Modifier.padding(horizontal = 8.dp).weight(1f),
        )
        OutlinedButton(onClick = onRefresh, enabled = !state.locating) { Text("Lấy lại") }
    }
}

/**
 * A read-only field that opens a menu.
 *
 * Every one of these is optional, so each carries a "Bỏ trống" row: a rep who
 * taps a value by mistake needs a way back to having chosen nothing, and without
 * it the only way out is to abandon the form.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Picker(
    label: String,
    options: List<NamedRef>,
    selectedId: String?,
    onSelect: (NamedRef?) -> Unit,
    emptyHint: String? = null,
) {
    var open by remember { mutableStateOf(false) }
    val selected = options.firstOrNull { it.id == selectedId }
    val enabled = options.isNotEmpty()

    Box {
        OutlinedTextField(
            value = selected?.name ?: "",
            onValueChange = {},
            readOnly = true,
            enabled = false,
            label = { Text(label) },
            placeholder = { Text(if (enabled) "Chọn" else emptyHint.orEmpty()) },
            trailingIcon = { Icon(Icons.Default.ArrowDropDown, contentDescription = null) },
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = enabled) { open = true },
        )

        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("Bỏ trống") },
                onClick = {
                    onSelect(null)
                    open = false
                },
            )
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.name) },
                    trailingIcon = {
                        if (option.id == selectedId) {
                            Icon(Icons.Default.Check, contentDescription = null)
                        }
                    },
                    onClick = {
                        onSelect(option)
                        open = false
                    },
                )
            }
        }
    }
}

@Composable
private fun RegisteredDialog(customer: RegisteredCustomer, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Xong") } },
        icon = { Icon(Icons.Default.Check, contentDescription = null) },
        title = { Text("Đã gửi đăng ký") },
        text = {
            Column {
                Text("${customer.name} — mã ${customer.code}")
                Text(
                    text = "Cửa hàng đã xuất hiện trong tuyến hôm nay để bán ngay. " +
                        "Văn phòng sẽ duyệt sau.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
    )
}

@Composable
private fun Notice(message: String) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
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

// -----------------------------------------------------------------------------
// Previews
// -----------------------------------------------------------------------------

private val SampleOptions = CustomerOptions(
    classes = listOf(NamedRef("c1", "A", "Loại A")),
    channels = listOf(NamedRef("ch1", "GT", "Truyền thống")),
    shopTypes = listOf(NamedRef("s1", "TH", "Tạp hoá")),
    provinces = listOf(NamedRef("p1", "79", "TP Hồ Chí Minh")),
)

@Preview(name = "Khách hàng mới", showBackground = true, heightDp = 900)
@Composable
private fun NewCustomerPreview() {
    MyeSalesTheme {
        NewCustomerContent(
            state = NewCustomerUiState(loading = false, options = SampleOptions),
            onBack = {},
            onDoneRegistering = {},
            onName = {},
            onPhone = {},
            onAddress = {},
            onNote = {},
            onProvince = {},
            onDistrict = {},
            onWard = {},
            onClass = {},
            onChannel = {},
            onShopType = {},
            onRefreshLocation = {},
            onSubmit = {},
        )
    }
}
