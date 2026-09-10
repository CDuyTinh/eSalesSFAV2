package com.tinhcd.myesalessfa.feature.incall.steps

import android.Manifest
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.tinhcd.myesalessfa.core.ui.LoadingBox
import com.tinhcd.myesalessfa.core.ui.PrimaryButton
import com.tinhcd.myesalessfa.domain.model.NearExpiryLot
import com.tinhcd.myesalessfa.domain.model.PricedProduct
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * Hàng cận date.
 *
 * The rep walks the shelf for stock about to expire and writes down each batch:
 * product, lot number, expiry, quantity. Not the stock count wearing a different
 * hat — that one asks how many are on the shelf, this one asks which batches are
 * about to go off, and a lot number has nowhere to live in the other's rows.
 *
 * It finishes with a photograph or a reason for not taking one. Evidence, or an
 * explanation of its absence, never neither — `alert_chosen_reason` over there,
 * and the same refusal in `submit_near_expiry_check` here.
 */
@Composable
fun NearExpiryScreen(
    onDone: () -> Unit,
    viewModel: NearExpiryViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    val takePicture = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { saved -> viewModel.onPhotoTaken(saved) }

    val cameraPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    LaunchedEffect(Unit) { cameraPermission.launch(Manifest.permission.CAMERA) }

    LaunchedEffect(state.finished) {
        if (state.finished) onDone()
    }

    var confirmLeave by remember { mutableStateOf(false) }

    // The lots were typed off cases the rep is standing in front of, and the
    // photos cannot be taken again once they have left the shop.
    val unsaved = (state.draft.lots.isNotEmpty() || state.draft.photos.isNotEmpty()) &&
        !state.finished
    val onPicker = state.page == NearExpiryPage.PICK_PRODUCT

    val back = {
        when {
            onPicker -> viewModel.onClosePicker()
            unsaved -> confirmLeave = true
            else -> onDone()
        }
    }

    BackHandler(enabled = onPicker || unsaved) { back() }

    Scaffold(
        topBar = {
            StepHeader(
                title = if (onPicker) "Chọn sản phẩm" else state.title.ifBlank { "Hàng cận date" },
                onBack = back,
                subtitle = when {
                    onPicker -> "Tìm mặt hàng cần ghi lô"
                    state.draft.lots.isEmpty() -> "Ghi từng lô sắp hết hạn trên kệ"
                    else -> "${state.draft.lots.size} lô · ${state.draft.totalBaseQty} đơn vị"
                },
            )
        },
    ) { padding ->
        when {
            state.loading -> LoadingBox(Modifier.padding(padding))

            onPicker -> ProductPicker(
                state = state,
                onQueryChange = viewModel::onQueryChange,
                onPick = viewModel::onPickProduct,
                modifier = Modifier.padding(padding),
            )

            else -> DocumentPage(
                state = state,
                onAdd = viewModel::onOpenPicker,
                onRemoveLot = viewModel::onRemoveLot,
                onNoteChange = viewModel::onNoteChange,
                onCapture = { takePicture.launch(viewModel.newPhotoTarget().uri) },
                onRemovePhoto = viewModel::onRemovePhoto,
                onNoPhotoReason = viewModel::onNoPhotoReason,
                onSubmit = viewModel::submit,
                onBack = back,
                modifier = Modifier.padding(padding),
            )
        }
    }

    state.addingFor?.let { product ->
        LotDialog(
            product = product,
            lotNoLength = state.draft.lotNoLength,
            onDismiss = viewModel::onDismissLotForm,
            onAdd = { lotNo, expiry, uom, qty ->
                viewModel.onAddLot(product, lotNo, expiry, uom, qty)
            },
        )
    }

    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text("Chưa lưu phiếu hàng cận date") },
            text = {
                Text(
                    "Đã ghi ${state.draft.lots.size} lô nhưng chưa lưu. " +
                        "Thoát bây giờ sẽ mất hết.",
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmLeave = false; onDone() }) { Text("Thoát") }
            },
            dismissButton = {
                TextButton(onClick = { confirmLeave = false }) { Text("Ở lại") }
            },
        )
    }
}

// -----------------------------------------------------------------------------
// The document
// -----------------------------------------------------------------------------

@Composable
private fun DocumentPage(
    state: NearExpiryUiState,
    onAdd: () -> Unit,
    onRemoveLot: (productId: String, lotNo: String) -> Unit,
    onNoteChange: (String) -> Unit,
    onCapture: () -> Unit,
    onRemovePhoto: (String) -> Unit,
    onNoPhotoReason: (String) -> Unit,
    onSubmit: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val draft = state.draft

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
            if (draft.lots.isEmpty()) {
                item(key = "empty") {
                    Text(
                        "Chưa ghi lô nào. Thêm từng lô hàng sắp hết hạn tìm thấy trên kệ.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 16.dp),
                    )
                }
            }

            // Grouped by product, because that is how the rep works the shelf:
            // one facing at a time, several batches behind it.
            draft.byProduct.forEach { (product, lots) ->
                item(key = "h-$product") {
                    Text(
                        product,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                items(lots, key = { it.key }) { lot ->
                    LotRow(lot = lot, onRemove = { onRemoveLot(lot.productId, lot.lotNo) })
                }
            }

            item(key = "add") {
                OutlinedButton(
                    onClick = onAdd,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp),
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Text("  Thêm lô")
                }
            }

            item(key = "evidence") {
                EvidenceCard(
                    state = state,
                    onCapture = onCapture,
                    onRemovePhoto = onRemovePhoto,
                    onNoPhotoReason = onNoPhotoReason,
                )
            }

            item(key = "note") {
                OutlinedTextField(
                    value = draft.note,
                    onValueChange = onNoteChange,
                    label = { Text("Ghi chú (tùy chọn)") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        Surface(shadowElevation = 8.dp) {
            Column(Modifier.padding(16.dp)) {
                state.error?.let { message ->
                    Text(
                        message,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.height(8.dp))
                }

                if (draft.lots.isNotEmpty() && !draft.evidenceSettled) {
                    Text(
                        "Cần một ảnh, hoặc chọn lý do không chụp được",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.height(6.dp))
                }

                PrimaryButton(
                    text = "Hoàn thành bước này",
                    onClick = onSubmit,
                    enabled = state.canSubmit,
                    loading = state.submitting,
                    height = 46.dp,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onBack,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp),
                ) { Text("Quay lại") }
            }
        }
    }
}

@Composable
private fun LotRow(lot: NearExpiryLot, onRemove: () -> Unit) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 14.dp, top = 6.dp, bottom = 6.dp, end = 4.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "Lô ${lot.lotNo}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    // The date is the point of the row, so it leads the caption.
                    "HSD ${lot.expiryDate} · ${lot.qty} ${lot.uomName}" +
                        if (lot.baseQty != lot.qty) " (${lot.baseQty} đơn vị)" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            TextButton(onClick = onRemove) {
                Icon(Icons.Default.Delete, contentDescription = "Bỏ lô ${lot.lotNo}")
            }
        }
    }
}

/**
 * A photograph, or a reason for its absence.
 *
 * The reasons only appear while there is no photograph: offering both at once
 * invites a rep to fill in the reason and then photograph it anyway, which
 * leaves head office two answers to one question.
 */
@Composable
private fun EvidenceCard(
    state: NearExpiryUiState,
    onCapture: () -> Unit,
    onRemovePhoto: (String) -> Unit,
    onNoPhotoReason: (String) -> Unit,
) {
    val draft = state.draft

    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                "Ảnh hàng cận date",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )

            Spacer(Modifier.height(8.dp))

            draft.photos.chunked(2).forEach { pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    pair.forEach { photo ->
                        Box(Modifier.weight(1f)) {
                            NearExpiryPhotoTile(
                                localPath = photo.localPath,
                                onRemove = { onRemovePhoto(photo.localPath) },
                            )
                        }
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
                Spacer(Modifier.height(8.dp))
            }

            if (draft.canAddPhoto) {
                OutlinedButton(
                    onClick = onCapture,
                    enabled = !state.capturing && !state.submitting,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp),
                ) {
                    Icon(Icons.Default.PhotoCamera, contentDescription = null)
                    Text(if (draft.photos.isEmpty()) "  Chụp ảnh" else "  Chụp thêm ảnh")
                }
            }

            if (!draft.hasPhoto && state.noPhotoReasons.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.height(10.dp))

                Text(
                    "Hoặc chọn lý do không chụp được",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))

                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.noPhotoReasons.forEach { reason ->
                        FilterChip(
                            selected = draft.noPhotoReasonId == reason.id,
                            onClick = { onNoPhotoReason(reason.id) },
                            label = { Text(reason.name) },
                        )
                    }
                }
            }
        }
    }
}

// -----------------------------------------------------------------------------
// Picking a product
// -----------------------------------------------------------------------------

@Composable
private fun ProductPicker(
    state: NearExpiryUiState,
    onQueryChange: (String) -> Unit,
    onPick: (PricedProduct) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxSize()
            .imePadding(),
    ) {
        OutlinedTextField(
            value = state.query,
            onValueChange = onQueryChange,
            label = { Text("Tìm tên hoặc mã sản phẩm") },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        )

        LazyColumn(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(state.visibleProducts, key = { it.product.id }) { product ->
                Card(
                    onClick = { onPick(product) },
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text(
                            product.product.name,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            product.product.code,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            if (state.visibleProducts.isEmpty()) {
                item(key = "none") {
                    Text(
                        "Không tìm thấy sản phẩm nào",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 24.dp),
                    )
                }
            }
        }
    }
}

/**
 * One batch: the number on the case, the date on it, and how many there are.
 *
 * The date is typed rather than picked from a calendar on purpose — a rep is
 * reading it off a carton and typing eight digits is faster than scrolling a
 * date picker back and forth, which is also how the legacy sheet does it.
 */
@Composable
private fun LotDialog(
    product: PricedProduct,
    lotNoLength: Int,
    onDismiss: () -> Unit,
    onAdd: (lotNo: String, expiryDate: String, uomCode: String, qty: Int) -> Unit,
) {
    var lotNo by remember { mutableStateOf("") }
    var expiry by remember { mutableStateOf("") }
    var qty by remember { mutableStateOf("") }
    var uom by remember {
        mutableStateOf(
            product.defaultUnit.unit.uomCode,
        )
    }

    val parsedDate = remember(expiry) { parseExpiry(expiry) }
    val ready = lotNo.trim().length == lotNoLength &&
        parsedDate != null &&
        (qty.toIntOrNull() ?: 0) > 0

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(product.product.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = lotNo,
                    onValueChange = { lotNo = it },
                    label = { Text("Số lô ($lotNoLength ký tự)") },
                    singleLine = true,
                    isError = lotNo.isNotEmpty() && lotNo.trim().length != lotNoLength,
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedTextField(
                    value = expiry,
                    onValueChange = { expiry = it },
                    label = { Text("Hạn dùng (dd/MM/yyyy)") },
                    singleLine = true,
                    isError = expiry.isNotEmpty() && parsedDate == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = qty,
                        onValueChange = { qty = it.filter(Char::isDigit) },
                        label = { Text("Số lượng") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )

                    Spacer(Modifier.width(8.dp))

                    // The unit the rep is holding. A case of twenty-four and
                    // twenty-four singles are the same quantity said differently,
                    // and the conversion travels with the row.
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        product.units.forEach { priced ->
                            FilterChip(
                                selected = uom == priced.unit.uomCode,
                                onClick = { uom = priced.unit.uomCode },
                                label = { Text(priced.unit.uomName) },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onAdd(lotNo, parsedDate!!.toString(), uom, qty.toIntOrNull() ?: 0) },
                enabled = ready,
            ) { Text("Thêm") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Huỷ") }
        },
    )
}

/**
 * Reads dd/MM/yyyy, and dd-MM-yyyy or eight bare digits too, because a rep
 * copying a date off a carton types whatever separator is under their thumb.
 * Null when it is not a date, which is what greys out the button.
 */
private fun parseExpiry(raw: String): LocalDate? {
    val digits = raw.filter(Char::isDigit)
    if (digits.length != 8) return null
    return try {
        LocalDate.of(
            digits.substring(4).toInt(),
            digits.substring(2, 4).toInt(),
            digits.substring(0, 2).toInt(),
        )
    } catch (_: DateTimeParseException) {
        null
    } catch (_: java.time.DateTimeException) {
        null
    }
}

/** One shot of the batch, with the way to take it back. */
@Composable
private fun NearExpiryPhotoTile(localPath: String, onRemove: () -> Unit) {
    Card(shape = RoundedCornerShape(12.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f),
        ) {
            AsyncImage(
                model = File(localPath),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )

            // Nothing is uploaded yet, so a shot the rep does not want goes with
            // the file behind it.
            FilledIconButton(
                onClick = onRemove,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(28.dp),
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Bỏ ảnh",
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}
