package com.tinhcd.myesalessfa.feature.incall.steps

import android.Manifest
import com.tinhcd.myesalessfa.R
import androidx.compose.ui.res.stringResource
import com.tinhcd.myesalessfa.core.R as CoreR
import com.tinhcd.myesalessfa.core.permission.rememberPermissionRequest
import com.tinhcd.myesalessfa.core.ui.theme.status
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.tinhcd.myesalessfa.core.ui.ButtonPair
import com.tinhcd.myesalessfa.core.ui.PrimaryButton
import com.tinhcd.myesalessfa.core.ui.SkeletonList
import com.tinhcd.myesalessfa.domain.model.DraftPosmCheck
import com.tinhcd.myesalessfa.domain.model.DraftPosmMovement
import com.tinhcd.myesalessfa.domain.model.DraftPosmRegistration
import com.tinhcd.myesalessfa.domain.model.PosmAtCustomer
import com.tinhcd.myesalessfa.domain.model.PosmCondition
import com.tinhcd.myesalessfa.domain.model.PosmMovementKind
import com.tinhcd.myesalessfa.domain.model.PosmPhoto
import com.tinhcd.myesalessfa.domain.model.PosmRegistration
import com.tinhcd.myesalessfa.domain.model.PosmRegistrationLine
import java.io.File

/**
 * Kiểm tra POSM.
 *
 * The company lends a shop a fridge, a three-tier rack, a light box. The step is
 * finding each one, counting it, saying what condition it is in and
 * photographing it — not answering four questions about whether a poster is hung
 * nicely, which is what this step used to be.
 *
 * Two tabs, as the legacy has: what is in the shop, and what the shop has asked
 * for. From those two lists come the other three things the rep does with POSM —
 * putting in for it, handing it over, taking it back — which used to be missing
 * here and are what InsertPosmRegis and InsertDeliverPosm do over there.
 */
@Composable
fun PosmScreen(
    onDone: () -> Unit,
    viewModel: PosmViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    val takePicture = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { saved -> viewModel.onPhotoTaken(saved) }

    // The manifest declares CAMERA, which makes ACTION_IMAGE_CAPTURE require the
    // grant on top of the FileProvider uri. Asked for on entry, so the rep is not
    // interrupted by a dialog after framing the shot.
    // Asked on entry, as before, and again from the capture button: refused,
    // the button explains why rather than opening a camera that cannot start.
    val cameraPermission = rememberPermissionRequest(
        listOf(Manifest.permission.CAMERA),
        stringResource(CoreR.string.permission_rationale_camera),
    )

    LaunchedEffect(Unit) { cameraPermission.launch() }

    LaunchedEffect(state.finished) {
        if (state.finished) onDone()
    }

    var confirmLeave by remember { mutableStateOf(false) }

    // Photos live on the device until the check is submitted, so leaving throws
    // them away — and a photograph of a damaged fridge cannot be typed again from
    // memory once the rep has left the shop.
    val unsaved = (
        state.check?.photos?.isNotEmpty() == true ||
            state.movement?.photos?.isNotEmpty() == true
        ) && !state.finished
    val onChecking = state.page == PosmPage.CHECK
    val onForm = state.page == PosmPage.REGISTER || state.page == PosmPage.MOVE

    val back = {
        when {
            unsaved -> confirmLeave = true
            onChecking -> viewModel.onLeaveItem()
            onForm -> viewModel.onLeaveForm()
            else -> onDone()
        }
    }

    BackHandler(enabled = unsaved || onChecking || onForm) { back() }

    Scaffold(
        topBar = {
            StepHeader(
                title = when {
                    onChecking -> state.check?.item?.itemName.orEmpty()
                    state.page == PosmPage.REGISTER -> stringResource(R.string.sfa_40)
                    state.page == PosmPage.MOVE -> state.movement?.kind?.label.orEmpty()
                    else -> state.title.ifBlank { "POSM" }
                },
                onBack = back,
                subtitle = when {
                    onChecking -> state.check?.item?.programName
                    state.page == PosmPage.REGISTER -> stringResource(R.string.sfb_100)
                    state.page == PosmPage.MOVE -> stringResource(R.string.sfb_101)
                    state.placed.isEmpty() -> null
                    else -> "Đã kiểm ${state.checkedCount}/${state.placed.size} vật phẩm"
                },
            )
        },
    ) { padding ->
        when {
            state.loading -> SkeletonList(Modifier.padding(padding), count = 4)

            onChecking && state.check != null -> CheckForm(
                check = state.check!!,
                capturing = state.capturing,
                submitting = state.submitting,
                error = state.error,
                onCapture = { cameraPermission.launch { takePicture.launch(viewModel.newPhotoTarget().uri) } },
                onRemove = viewModel::onRemovePhoto,
                onCountedQtyChange = viewModel::onCountedQtyChange,
                onConditionChange = viewModel::onConditionChange,
                onRemarkChange = viewModel::onRemarkChange,
                onSuggestionChange = viewModel::onSuggestionChange,
                onSubmit = viewModel::submit,
                onBack = back,
                modifier = Modifier.padding(padding),
            )

            state.page == PosmPage.REGISTER && state.registration != null -> RegisterForm(
                draft = state.registration!!,
                submitting = state.submitting,
                error = state.error,
                onQty = viewModel::onRegisterQty,
                onReason = viewModel::onRegisterReason,
                onSubmit = viewModel::submitRegistration,
                onBack = back,
                modifier = Modifier.padding(padding),
            )

            state.page == PosmPage.MOVE && state.movement != null -> MovementForm(
                draft = state.movement!!,
                capturing = state.capturing,
                submitting = state.submitting,
                error = state.error,
                onQty = viewModel::onMovementQty,
                onNote = viewModel::onMovementNote,
                onCapture = { cameraPermission.launch { takePicture.launch(viewModel.newPhotoTarget().uri) } },
                onRemovePhoto = viewModel::onRemovePhoto,
                onSubmit = viewModel::submitMovement,
                onBack = back,
                modifier = Modifier.padding(padding),
            )

            else -> PosmList(
                state = state,
                onTabChange = viewModel::onTabChange,
                onOpen = viewModel::onOpenItem,
                onRecall = viewModel::onOpenRecall,
                onDeliver = viewModel::onOpenDelivery,
                onRegister = viewModel::onOpenRegister,
                onCompleteEmpty = viewModel::completeEmpty,
                onBack = onDone,
                modifier = Modifier.padding(padding),
            )
        }
    }

    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text(stringResource(R.string.sfa_37)) },
            text = {
                Text(
                    "Đã chụp ${state.check?.photoCount ?: 0} ảnh nhưng chưa lưu. " +
                        stringResource(R.string.sfb_63),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmLeave = false
                        viewModel.onLeaveItem()
                    },
                ) { Text(stringResource(R.string.sfa_14)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmLeave = false }) { Text(stringResource(R.string.sfa_15)) }
            },
        )
    }
}

// -----------------------------------------------------------------------------
// The list, in two tabs
// -----------------------------------------------------------------------------

@Composable
private fun PosmList(
    state: PosmUiState,
    onTabChange: (PosmTab) -> Unit,
    onOpen: (PosmAtCustomer) -> Unit,
    onRecall: (PosmAtCustomer) -> Unit,
    onDeliver: (PosmRegistration) -> Unit,
    onRegister: () -> Unit,
    onCompleteEmpty: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        TabRow(selected = state.tab, onSelect = onTabChange, registered = state.registrations.size)

        Box(Modifier.weight(1f)) {
            when {
                state.tab == PosmTab.IN_USE && state.placed.isEmpty() -> EmptyNote(
                    stringResource(R.string.sfb_102),
                )

                state.tab == PosmTab.IN_USE -> LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    // Grouped by programme, as the legacy list is: two racks from
                    // different programmes are two different loans, and the rep is
                    // answering to whoever ran each one.
                    state.placed
                        .groupBy { it.programName }
                        .forEach { (program, items) ->
                            item(key = "h-$program") {
                                Text(
                                    program,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                            }
                            items(items, key = { it.programId + it.itemId }) { item ->
                                PosmItemCard(
                                    item = item,
                                    onClick = { onOpen(item) },
                                    onRecall = { onRecall(item) },
                                )
                            }
                        }
                }

                state.registrations.isEmpty() -> EmptyNote(
                    stringResource(R.string.sfb_103),
                )

                else -> LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(
                        state.registrations,
                        key = { it.programId + it.itemId },
                    ) { registration ->
                        RegistrationCard(
                            registration = registration,
                            onDeliver = { onDeliver(registration) },
                        )
                    }
                }
            }
        }

        Surface(shadowElevation = 8.dp) {
            Column(Modifier.padding(16.dp)) {
                if (state.error != null) {
                    Text(
                        state.error,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.height(8.dp))
                }

                // Nothing in the shop is a real answer, and the rep needs a way to
                // give it. Without this the step would sit unfinished forever on
                // every outlet that holds none of the company's furniture.
                //
                // Registering is on the registration tab, because that is where
                // the rep is already looking at what the shop has asked for, and
                // hidden when the branch is running no POSM programme at all —
                // there would be nothing on the form.
                val canRegister = state.tab == PosmTab.REGISTERED && state.catalogue.any { it.canRegister }
                when {
                    // Three actions: registering takes the row above the pair.
                    state.nothingPlaced && canRegister -> {
                        OutlinedButton(
                            onClick = onRegister,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(44.dp),
                        ) {
                            Icon(Icons.Default.AddCircleOutline, contentDescription = null)
                            Text(stringResource(R.string.sfa_38))
                        }
                        Spacer(Modifier.height(8.dp))
                        ButtonPair(
                            text = stringResource(R.string.sfa_39),
                            onClick = onCompleteEmpty,
                            onBack = onBack,
                            loading = state.submitting,
                            height = 44.dp,
                        )
                    }

                    state.nothingPlaced -> ButtonPair(
                        text = stringResource(R.string.sfa_39),
                        onClick = onCompleteEmpty,
                        onBack = onBack,
                        loading = state.submitting,
                        height = 44.dp,
                    )

                    canRegister -> ButtonPair(
                        text = stringResource(R.string.sfa_40),
                        onClick = onRegister,
                        onBack = onBack,
                        height = 44.dp,
                    )

                    else -> OutlinedButton(
                        onClick = onBack,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp),
                    ) { Text(stringResource(R.string.action_back)) }
                }
            }
        }
    }
}

@Composable
private fun TabRow(selected: PosmTab, onSelect: (PosmTab) -> Unit, registered: Int) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        TabButton(
            text = stringResource(R.string.sfa_41),
            selected = selected == PosmTab.IN_USE,
            onClick = { onSelect(PosmTab.IN_USE) },
            modifier = Modifier.weight(1f),
        )
        TabButton(
            text = if (registered > 0) "Đã đăng ký ($registered)" else "Đã đăng ký",
            selected = selected == PosmTab.REGISTERED,
            onClick = { onSelect(PosmTab.REGISTERED) },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun TabButton(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (selected) scheme.primary else scheme.surface,
        border = if (selected) null else CardDefaults.outlinedCardBorder(),
        modifier = modifier
            .height(38.dp)
            .clickable(onClick = onClick),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = if (selected) scheme.onPrimary else scheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EmptyNote(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(32.dp),
        )
    }
}

@Composable
private fun PosmItemCard(item: PosmAtCustomer, onClick: () -> Unit, onRecall: () -> Unit) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PosmThumbnail(item.imageUrl)
                Spacer(Modifier.width(12.dp))

                Column(Modifier.weight(1f)) {
                    Text(
                        item.itemName,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        "${item.itemCode} | ${item.unitName}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (item.isChecked) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = if (item.condition == PosmCondition.USABLE) Good else Bad,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                }

                Icon(
                    Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.outline,
                )
            }

            Spacer(Modifier.height(8.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(8.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Đã cấp ${item.placedQty} ${item.unitName}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )

                val condition = item.condition
                if (condition != null) {
                    StatusChip(condition.label, conditionColour(condition))
                } else {
                    StatusChip(stringResource(R.string.sfb_104), MaterialTheme.colorScheme.outline)
                }
            }

            if (item.isChecked && item.countedQty != null) {
                Text(
                    "Thực tế ${item.countedQty} ${item.unitName} - ${item.photoCount} ảnh",
                    style = MaterialTheme.typography.labelSmall,
                    // Fewer than the records claim is the finding this step exists
                    // to surface, so it is coloured rather than buried in grey.
                    color = if (item.isShort(item.countedQty!!)) {
                        Bad
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }

            Spacer(Modifier.height(8.dp))
            // A text button rather than one more filled control: taking a fridge
            // back is the rarer act on this card, and the common one is opening
            // the check by tapping anywhere on it.
            TextButton(onClick = onRecall) { Text(stringResource(R.string.sfa_42)) }
        }
    }
}

@Composable
private fun RegistrationCard(registration: PosmRegistration, onDeliver: () -> Unit) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        registration.itemName,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        registration.programName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                when {
                    registration.isPending ->
                        StatusChip(stringResource(R.string.sfa_20), MaterialTheme.colorScheme.tertiary)

                    registration.isRejected -> StatusChip(stringResource(R.string.sfb_105), Bad)
                    else -> StatusChip(stringResource(R.string.sfb_15), Good)
                }
            }

            Spacer(Modifier.height(8.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(8.dp))

            // Three numbers, because the gap between them is the whole story: what
            // was asked for, what was allowed, what actually arrived.
            Row {
                QtyCell(stringResource(R.string.sfb_28), registration.registeredQty, Modifier.weight(1f))
                QtyCell(stringResource(R.string.sfb_106), registration.approvedQty, Modifier.weight(1f))
                QtyCell(stringResource(R.string.sfb_107), registration.deliveredQty, Modifier.weight(1f))
            }

            if (registration.awaitingDelivery > 0) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "Còn ${registration.awaitingDelivery} ${registration.unitName} chưa giao",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
                Spacer(Modifier.height(8.dp))
                // Only where something is actually owed. A button that opens a
                // form with nothing to move is a button that wastes a trip.
                OutlinedButton(
                    onClick = onDeliver,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(40.dp),
                ) { Text(stringResource(R.string.sfa_43)) }
            }
        }
    }
}

@Composable
private fun QtyCell(label: String, value: Int, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "$value",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

// -----------------------------------------------------------------------------
// One asset's check
// -----------------------------------------------------------------------------

@Composable
private fun CheckForm(
    check: DraftPosmCheck,
    capturing: Boolean,
    submitting: Boolean,
    error: String?,
    onCapture: () -> Unit,
    onRemove: (String) -> Unit,
    onCountedQtyChange: (Int) -> Unit,
    onConditionChange: (PosmCondition) -> Unit,
    onRemarkChange: (String) -> Unit,
    onSuggestionChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize().imePadding()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PosmThumbnail(check.item.imageUrl)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                check.item.itemName,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                "${check.item.itemCode} | ${check.item.unitName}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    Spacer(Modifier.height(10.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Spacer(Modifier.height(10.dp))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.sfb_108),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            "${check.item.placedQty}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }

                    Spacer(Modifier.height(8.dp))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.sfb_109),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f),
                        )
                        QtyStepper(
                            qty = check.countedQty,
                            onQtyChange = onCountedQtyChange,
                            placeholder = "0",
                        )
                    }

                    val counted = check.countedQty
                    if (counted != null && check.item.isShort(counted)) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Thiếu ${check.item.placedQty - counted} so với số đã cấp",
                            style = MaterialTheme.typography.labelSmall,
                            color = Bad,
                        )
                    }

                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.sfa_44), style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(6.dp))

                    // Three buttons rather than a picker: there are exactly three
                    // answers, and hiding them behind a sheet makes the rep tap
                    // twice to say the only thing this screen is asking.
                    PosmCondition.entries.forEach { option ->
                        ConditionRow(
                            condition = option,
                            selected = check.condition == option,
                            onClick = { onConditionChange(option) },
                        )
                        Spacer(Modifier.height(6.dp))
                    }
                }
            }

            OutlinedTextField(
                value = check.remark,
                onValueChange = onRemarkChange,
                label = { Text(stringResource(R.string.sfa_19)) },
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = check.suggestion,
                onValueChange = onSuggestionChange,
                label = { Text(stringResource(R.string.sfa_45)) },
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )

            Text(
                "Ảnh POSM - tối thiểu ${check.photoMin}, tối đa ${check.photoMax}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // Laid out by hand rather than with a lazy grid: this column scrolls,
            // and a lazy grid inside a scrolling parent has no height to measure.
            check.photos.chunked(2).forEach { pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    pair.forEach { photo ->
                        Box(Modifier.weight(1f)) {
                            PosmPhotoThumbnail(
                                photo = photo,
                                onRemove = { onRemove(photo.localPath) },
                            )
                        }
                    }
                    // Keeps a lone last photo half-width instead of stretching it.
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }

            if (check.canAddPhoto) {
                OutlinedButton(
                    onClick = onCapture,
                    enabled = !capturing && !submitting,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp),
                ) {
                    Icon(Icons.Default.PhotoCamera, contentDescription = null)
                    Text(if (check.photos.isEmpty()) stringResource(R.string.sfb_64) else stringResource(R.string.sfb_65))
                }
            } else {
                Text(
                    "Đã đủ ${check.photoMax} ảnh. Bỏ bớt một ảnh nếu muốn chụp lại.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (error != null) {
                Text(
                    error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }

        Surface(shadowElevation = 8.dp) {
            Column(Modifier.padding(16.dp)) {
                when {
                    check.countedQty == null -> Text(
                        stringResource(R.string.sfb_110),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )

                    check.condition == null -> Text(
                        stringResource(R.string.sfb_111),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )

                    check.photosStillNeeded > 0 -> Text(
                        "Còn thiếu ${check.photosStillNeeded} ảnh",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )

                    else -> Text(
                        // The size is the honest answer to "why is this slow" on a
                        // connection measured in tens of kilobytes per second.
                        "${check.photoCount} ảnh - ${check.totalSizeBytes / 1024} KB sẽ được gửi",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Spacer(Modifier.height(8.dp))

                ButtonPair(
                    text = stringResource(R.string.sfa_46),
                    onClick = onSubmit,
                    onBack = onBack,
                    enabled = check.canSubmit,
                    loading = submitting || capturing,
                    height = 44.dp,
                )
            }
        }
    }
}

/**
 * Neither answer is the default. Nothing is selected until the rep says so,
 * because a pre-selected condition is a condition nobody assessed.
 */
@Composable
private fun ConditionRow(
    condition: PosmCondition,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val tint = conditionColour(condition)
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (selected) tint else MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .height(42.dp)
            .clickable(onClick = onClick),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                condition.label,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = if (selected) MaterialTheme.status.onStatus else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PosmThumbnail(imageUrl: String?) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.size(44.dp),
    ) {
        if (imageUrl.isNullOrBlank()) {
            // The catalogue rarely carries pictures, and an empty grey square says
            // less than the shape of the thing being counted.
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Default.Inventory2,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        } else {
            AsyncImage(
                model = imageUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun PosmPhotoThumbnail(photo: PosmPhoto, onRemove: () -> Unit) {
    Card(
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f),
        ) {
            // Loaded from the local file: nothing has been uploaded yet, and the rep
            // needs to see the shot they just took to judge whether to keep it.
            AsyncImage(
                model = File(photo.localPath),
                contentDescription = stringResource(R.string.sfa_47),
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )

            FilledIconButton(
                onClick = onRemove,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(2.dp)
                    .size(28.dp),
            ) { Icon(Icons.Default.Close, contentDescription = stringResource(R.string.sfa_23)) }

            Row(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.35f))
                    .padding(horizontal = 6.dp, vertical = 3.dp),
            ) {
                Text(
                    "${photo.sizeBytes / 1024} KB",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium,
                    color = Color.White,
                )
            }
        }
    }
}

@Composable
private fun StatusChip(text: String, tint: Color) {
    Surface(color = tint.copy(alpha = 0.12f), shape = RoundedCornerShape(6.dp)) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            color = tint,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

/** Fine, fixable, finished — the three conditions, in three readable colours. */
@Composable
private fun conditionColour(condition: PosmCondition): Color = when (condition) {
    PosmCondition.USABLE -> Good
    PosmCondition.REPAIRABLE -> Warn
    PosmCondition.UNUSABLE -> Bad
}

private val Good: Color
    @Composable get() = MaterialTheme.status.success
private val Warn: Color
    @Composable get() = MaterialTheme.status.warning
private val Bad: Color
    @Composable get() = MaterialTheme.status.danger

// -----------------------------------------------------------------------------
// Đăng ký POSM
// -----------------------------------------------------------------------------

/**
 * Asking head office for POSM this outlet does not have.
 *
 * A ceiling per asset where the programme sets one, counting what the shop
 * already holds: a shop allowed two racks that has one may ask for one more. The
 * server applies the same arithmetic, so nothing here can be sent that will come
 * back refused.
 */
@Composable
private fun RegisterForm(
    draft: DraftPosmRegistration,
    submitting: Boolean,
    error: String?,
    onQty: (programId: String, itemId: String, qty: Int) -> Unit,
    onReason: (String) -> Unit,
    onSubmit: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxSize()
            .imePadding(),
    ) {
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            draft.lines
                .groupBy { it.entry.programName }
                .forEach { (program, lines) ->
                    item(key = "rh-$program") {
                        Text(
                            program,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    items(lines, key = { it.entry.programId + it.entry.itemId }) { line ->
                        RegisterLineCard(line = line, onQty = onQty)
                    }
                }

            item(key = "reason") {
                OutlinedTextField(
                    value = draft.reason,
                    onValueChange = onReason,
                    label = { Text(stringResource(R.string.sfa_48)) },
                    minLines = 2,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                )
            }
        }

        Surface(shadowElevation = 8.dp) {
            Column(Modifier.padding(16.dp)) {
                if (error != null) {
                    Text(
                        error,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.height(8.dp))
                }

                ButtonPair(
                    text = stringResource(R.string.sfa_49),
                    onClick = onSubmit,
                    onBack = onBack,
                    enabled = draft.canSubmit && !submitting,
                    loading = submitting,
                )
            }
        }
    }
}

@Composable
private fun RegisterLineCard(
    line: PosmRegistrationLine,
    onQty: (programId: String, itemId: String, qty: Int) -> Unit,
) {
    val entry = line.entry
    val ceiling = entry.ceiling
    val room = if (ceiling == 0) Int.MAX_VALUE else (ceiling - entry.placedQty).coerceAtLeast(0)

    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PosmThumbnail(entry.imageUrl)
                Spacer(Modifier.width(12.dp))

                Column(Modifier.weight(1f)) {
                    Text(
                        entry.itemName,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        buildString {
                            append(entry.itemCode)
                            if (ceiling > 0) append(" | tối đa $ceiling ${entry.unitName}")
                            if (entry.placedQty > 0) append(" | đang có ${entry.placedQty}")
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (entry.isPending) {
                    StatusChip(stringResource(R.string.sfa_20), MaterialTheme.colorScheme.tertiary)
                }
            }

            Spacer(Modifier.height(8.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (room == 0) stringResource(R.string.sfb_112) else stringResource(R.string.sfb_113),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (room == 0) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    modifier = Modifier.weight(1f),
                )

                if (room > 0) {
                    QtyStepper(
                        qty = line.qty,
                        onQtyChange = { onQty(entry.programId, entry.itemId, it) },
                        placeholder = "0",
                    )
                }
            }
        }
    }
}

// -----------------------------------------------------------------------------
// Giao và thu hồi
// -----------------------------------------------------------------------------

/**
 * Handing assets over, or taking them back.
 *
 * One form for both, as the legacy uses one screen for its two order types: the
 * quantities and the evidence are the same, only the direction differs. The
 * photograph is not optional — `submit_posm_movement` refuses a handover without
 * one, the way `posm_image_mess_required` does over there.
 */
@Composable
private fun MovementForm(
    draft: DraftPosmMovement,
    capturing: Boolean,
    submitting: Boolean,
    error: String?,
    onQty: (programId: String, itemId: String, qty: Int) -> Unit,
    onNote: (String) -> Unit,
    onCapture: () -> Unit,
    onRemovePhoto: (String) -> Unit,
    onSubmit: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val delivering = draft.kind == PosmMovementKind.DELIVERY

    Column(
        modifier
            .fillMaxSize()
            .imePadding(),
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            draft.lines.forEach { line ->
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text(
                            line.itemName,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            if (delivering) {
                                "Được giao tối đa ${line.available} ${line.unitName}"
                            } else {
                                "Cửa hàng đang giữ ${line.available} ${line.unitName}"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        Spacer(Modifier.height(10.dp))

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (delivering) stringResource(R.string.sfb_114) else stringResource(R.string.sfb_115),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                            QtyStepper(
                                qty = line.qty,
                                onQtyChange = { onQty(line.programId, line.itemId, it) },
                                placeholder = "0",
                            )
                        }
                    }
                }
            }

            Text(
                "Ảnh bàn giao - tối thiểu ${draft.photoMin}, tối đa ${draft.photoMax}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            draft.photos.chunked(2).forEach { pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    pair.forEach { photo ->
                        Box(Modifier.weight(1f)) {
                            PosmPhotoThumbnail(
                                photo = photo,
                                onRemove = { onRemovePhoto(photo.localPath) },
                            )
                        }
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }

            if (draft.canAddPhoto) {
                OutlinedButton(
                    onClick = onCapture,
                    enabled = !capturing && !submitting,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp),
                ) {
                    Icon(Icons.Default.PhotoCamera, contentDescription = null)
                    Text(if (draft.photos.isEmpty()) stringResource(R.string.sfb_64) else stringResource(R.string.sfb_65))
                }
            }

            OutlinedTextField(
                value = draft.note,
                onValueChange = onNote,
                label = { Text(stringResource(R.string.sfa_19)) },
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Surface(shadowElevation = 8.dp) {
            Column(Modifier.padding(16.dp)) {
                if (error != null) {
                    Text(
                        error,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.height(8.dp))
                }

                if (draft.photosStillNeeded > 0) {
                    Text(
                        "Còn thiếu ${draft.photosStillNeeded} ảnh",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.height(6.dp))
                } else if (draft.movingLines.isEmpty()) {
                    Text(
                        stringResource(R.string.sfb_116),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                }

                ButtonPair(
                    text = if (delivering) stringResource(R.string.sfb_117) else stringResource(R.string.sfb_118),
                    onClick = onSubmit,
                    onBack = onBack,
                    enabled = draft.canSubmit && !submitting && !capturing,
                    loading = submitting,
                )
            }
        }
    }
}
