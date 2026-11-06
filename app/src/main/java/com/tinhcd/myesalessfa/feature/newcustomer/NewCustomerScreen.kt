package com.tinhcd.myesalessfa.feature.newcustomer

import androidx.compose.foundation.clickable
import com.tinhcd.myesalessfa.R
import com.tinhcd.myesalessfa.core.R as CoreR
import com.tinhcd.myesalessfa.core.permission.rememberPermissionRequest
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.LaunchedEffect
import android.Manifest
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tinhcd.myesalessfa.core.ui.ButtonPair
import com.tinhcd.myesalessfa.core.ui.HeaderShape
import com.tinhcd.myesalessfa.core.ui.SkeletonForm
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

    // Never asked for here before: the screen relied on an earlier one having
    // been granted, and on a phone where it was not, the fix was silently missing.
    val locationPermission = rememberPermissionRequest(
        listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
        stringResource(CoreR.string.permission_rationale_location),
        anyOf = true,
    )
    LaunchedEffect(Unit) { locationPermission.launch(viewModel::captureLocation) }

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
                modifier = Modifier.clip(HeaderShape),
                title = { Text(stringResource(R.string.sfa_73)) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.brand.header,
                    titleContentColor = MaterialTheme.brand.onHeader,
                ),
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (state.loading) {
                SkeletonForm(fields = 6)
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item {
                        SectionTitle(stringResource(R.string.sfa_87))
                    }
                    item {
                        OutlinedTextField(
                            value = state.draft.name,
                            onValueChange = onName,
                            label = { Text(stringResource(R.string.sfa_86)) },
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
                            label = { Text(stringResource(R.string.order_address)) },
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

                    item { SectionTitle(stringResource(R.string.sfa_88)) }
                    item {
                        OutlinedTextField(
                            value = state.draft.phone,
                            onValueChange = onPhone,
                            label = { Text(stringResource(R.string.sfa_89)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    item {
                        Picker(
                            label = stringResource(R.string.sfa_90),
                            options = state.options.provinces,
                            selectedId = state.draft.provinceId,
                            onSelect = onProvince,
                        )
                    }
                    item {
                        Picker(
                            label = stringResource(R.string.sfa_91),
                            options = state.districts,
                            selectedId = state.draft.districtId,
                            onSelect = onDistrict,
                            emptyHint = stringResource(R.string.sfb_150),
                        )
                    }
                    item {
                        Picker(
                            label = stringResource(R.string.sfa_92),
                            options = state.wards,
                            selectedId = state.draft.wardId,
                            onSelect = onWard,
                            emptyHint = stringResource(R.string.sfb_151),
                        )
                    }
                    item {
                        Picker(
                            label = stringResource(R.string.sfa_93),
                            options = state.options.classes,
                            selectedId = state.draft.classId,
                            onSelect = onClass,
                        )
                    }
                    item {
                        Picker(
                            label = stringResource(R.string.sfa_94),
                            options = state.options.channels,
                            selectedId = state.draft.channelId,
                            onSelect = onChannel,
                        )
                    }
                    item {
                        Picker(
                            label = stringResource(R.string.sfa_95),
                            options = state.options.shopTypes,
                            selectedId = state.draft.shopTypeId,
                            onSelect = onShopType,
                        )
                    }
                    item {
                        OutlinedTextField(
                            value = state.draft.note,
                            onValueChange = onNote,
                            label = { Text(stringResource(R.string.sfa_96)) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    state.error?.let { message ->
                        item { Notice(message) }
                    }

                    item {
                        ButtonPair(
                            text = stringResource(R.string.sfa_49),
                            onClick = onSubmit,
                            onBack = onBack,
                            loading = state.submitting,
                            modifier = Modifier.padding(top = 8.dp),
                        )
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
        state.locating -> stringResource(R.string.sfb_152) to scheme.onSurfaceVariant
        point != null -> stringResource(R.string.sfb_153) to scheme.primary
        else -> stringResource(R.string.sfb_154) to scheme.secondary
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
        OutlinedButton(onClick = onRefresh, enabled = !state.locating) { Text(stringResource(R.string.sfa_97)) }
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
            placeholder = { Text(if (enabled) stringResource(R.string.sfa_72) else emptyHint.orEmpty()) },
            trailingIcon = { Icon(Icons.Default.ArrowDropDown, contentDescription = null) },
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = enabled) { open = true },
        )

        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.sfa_98)) },
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
        title = { Text(stringResource(R.string.sfa_99)) },
        text = {
            Column {
                Text("${customer.name} — mã ${customer.code}")
                Text(
                    text = stringResource(R.string.sfa_100) +
                        stringResource(R.string.sfb_155),
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
