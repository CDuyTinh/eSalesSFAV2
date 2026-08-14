package com.tinhcd.myesalessfa.feature.checkin

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tinhcd.myesalessfa.core.ui.ErrorBox
import com.tinhcd.myesalessfa.core.ui.LoadingBox
import com.tinhcd.myesalessfa.core.ui.PrimaryButton
import com.tinhcd.myesalessfa.domain.model.CheckInGate
import com.tinhcd.myesalessfa.domain.model.ReasonCode
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CheckInScreen(
    onDone: () -> Unit,
    viewModel: CheckInViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { viewModel.refreshLocation() }

    // Ask once on entry. The check-in rules need a fix before they can say
    // anything useful.
    LaunchedEffect(Unit) {
        permissionLauncher.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            ),
        )
    }

    LaunchedEffect(state.finished) {
        if (state.finished) onDone()
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Check-in") }) },
    ) { padding ->
        when {
            state.loading -> LoadingBox(Modifier.padding(padding))
            state.customer == null -> ErrorBox(
                state.error ?: "Khong tim thay khach hang trong tuyen hom nay",
                modifier = Modifier.padding(padding),
            )

            else -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val customer = state.customer!!
                Text(
                    customer.name,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    customer.code + (customer.address?.let { "\n$it" } ?: ""),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                GateCard(
                    gate = state.gate,
                    locating = state.locating,
                    onRefresh = viewModel::refreshLocation,
                )

                if (state.needsReason) {
                    val kind = (state.gate as CheckInGate.NeedsReason).kind
                    Text(kind.prompt(), style = MaterialTheme.typography.bodyLarge)
                    state.reasons.forEach { reason ->
                        ReasonRow(
                            reason = reason,
                            selected = state.selectedReason?.id == reason.id,
                            onClick = { viewModel.selectReason(reason) },
                        )
                    }
                    if (state.reasons.isEmpty()) {
                        Text(
                            "Chua tai duoc danh sach ly do. Kiem tra ket noi roi thu lai.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }

                if (state.error != null) {
                    Text(
                        state.error.orEmpty(),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }

                Spacer(Modifier.height(4.dp))

                PrimaryButton(
                    text = "Check-in",
                    onClick = viewModel::submit,
                    enabled = state.canSubmit,
                    loading = state.submitting,
                )

                OutlinedButton(
                    onClick = onDone,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Quay lai") }
            }
        }
    }
}

@Composable
private fun GateCard(
    gate: CheckInGate?,
    locating: Boolean,
    onRefresh: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val (text, tint) = when {
        locating -> "Dang lay vi tri..." to scheme.onSurfaceVariant
        gate is CheckInGate.Allowed ->
            "Trong ban kinh cho phep (${gate.distanceM.roundToInt()} m)" to scheme.primary

        gate is CheckInGate.NeedsReason -> {
            val d = gate.distanceM?.let { " (${it.roundToInt()} m)" }.orEmpty()
            "Can chon ly do$d" to scheme.secondary
        }

        gate is CheckInGate.Blocked ->
            "Khong the check-in tai vi tri nay" to scheme.error

        else -> "Chua xac dinh vi tri" to scheme.onSurfaceVariant
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = scheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.LocationOn, contentDescription = null, tint = tint)
            Text(
                text = text,
                style = MaterialTheme.typography.bodyLarge,
                color = tint,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 10.dp),
            )
            OutlinedButton(onClick = onRefresh, enabled = !locating) { Text("Lam moi") }
        }
    }
}

@Composable
private fun ReasonRow(reason: ReasonCode, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(
                color = if (selected) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surface
                },
                shape = RoundedCornerShape(10.dp),
            )
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (selected) {
                Icons.Default.RadioButtonChecked
            } else {
                Icons.Default.RadioButtonUnchecked
            },
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = reason.name,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(start = 10.dp),
        )
    }
}
