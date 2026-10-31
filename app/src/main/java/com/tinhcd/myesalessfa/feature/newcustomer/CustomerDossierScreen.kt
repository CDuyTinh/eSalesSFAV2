package com.tinhcd.myesalessfa.feature.newcustomer

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.tinhcd.myesalessfa.core.ui.ErrorBox
import com.tinhcd.myesalessfa.core.ui.LoadingBox
import com.tinhcd.myesalessfa.core.ui.theme.MyeSalesTheme
import com.tinhcd.myesalessfa.core.ui.theme.brand
import com.tinhcd.myesalessfa.domain.model.CustomerDraft
import com.tinhcd.myesalessfa.domain.model.CustomerPhotoType
import com.tinhcd.myesalessfa.domain.model.CustomerRegistrationBoard
import com.tinhcd.myesalessfa.domain.model.CustomerRegistrationDetail
import com.tinhcd.myesalessfa.domain.model.CustomerRegistrationPhoto
import com.tinhcd.myesalessfa.domain.model.CustomerRegistrationRow
import com.tinhcd.myesalessfa.domain.model.RegistrationStatus

/**
 * Hồ sơ khách hàng mới.
 *
 * The list of what this rep filed, what became of it, and — while a registration
 * is still waiting — the ability to correct it, photograph it, or withdraw it.
 * Registering a new outlet is still the old form, reached from the button.
 */
@Composable
fun CustomerDossierScreen(
    onBack: () -> Unit,
    onRegisterNew: () -> Unit,
    viewModel: CustomerDossierViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    val takePicture = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { saved -> viewModel.onPhotoTaken(saved) }

    val cameraPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    LaunchedEffect(Unit) { cameraPermission.launch(Manifest.permission.CAMERA) }

    // Registering a new outlet happens on a route of its own, so coming back
    // from it does not recompose this one -- the list would still be showing
    // what it held before the registration existed. Only on the list: reloading
    // under an open detail would swap what the rep is reading.
    LifecycleResumeEffect(state.page) {
        if (state.page == DossierPage.LIST) viewModel.load()
        onPauseOrDispose { }
    }

    when (state.page) {
        DossierPage.LIST -> ListPage(
            state = state,
            onBack = onBack,
            onRetry = viewModel::load,
            onQuery = viewModel::onQuery,
            onStatusFilter = viewModel::onStatusFilter,
            onOpen = viewModel::onOpen,
            onRegisterNew = onRegisterNew,
            onDismissMessage = viewModel::dismissMessage,
        )

        DossierPage.DETAIL -> DetailPage(
            state = state,
            onBack = viewModel::onBackToList,
            onEdit = viewModel::onEdit,
            onDelete = viewModel::onDelete,
            onCapture = { type -> takePicture.launch(viewModel.newPhotoTarget(type).uri) },
            onDismissMessage = viewModel::dismissMessage,
        )

        DossierPage.EDIT -> EditPage(
            state = state,
            onBack = viewModel::onCancelEdit,
            onName = viewModel::onName,
            onPhone = viewModel::onPhone,
            onAddress = viewModel::onAddress,
            onNote = viewModel::onNote,
            onSave = viewModel::save,
        )
    }
}

// -----------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ListPage(
    state: CustomerDossierUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onQuery: (String) -> Unit,
    onStatusFilter: (RegistrationStatus?) -> Unit,
    onOpen: (CustomerRegistrationRow) -> Unit,
    onRegisterNew: () -> Unit,
    onDismissMessage: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Khách hàng mới") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Quay lại")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.brand.header,
                    titleContentColor = MaterialTheme.brand.onHeader,
                    navigationIconContentColor = MaterialTheme.brand.onHeader,
                ),
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onRegisterNew) {
                Icon(Icons.Default.Add, contentDescription = "Đăng ký khách hàng mới")
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                state.loading && state.board.items.isEmpty() -> LoadingBox()
                state.error != null && state.board.items.isEmpty() ->
                    ErrorBox(state.error, onRetry = onRetry)

                else -> Column(Modifier.fillMaxSize()) {
                    OutlinedTextField(
                        value = state.query,
                        onValueChange = onQuery,
                        label = { Text("Tìm theo tên, mã hoặc số điện thoại") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                    )

                    if (state.board.presentStatuses.isNotEmpty()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(horizontal = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            FilterChip(
                                selected = state.statusFilter == null,
                                onClick = { onStatusFilter(null) },
                                label = { Text("Tất cả ${state.board.total}") },
                            )
                            state.board.presentStatuses.forEach { status ->
                                FilterChip(
                                    selected = state.statusFilter == status,
                                    onClick = { onStatusFilter(status) },
                                    label = { Text("${status.label} ${state.board.count(status)}") },
                                )
                            }
                        }
                    }

                    if (state.message != null || state.error != null) {
                        Notice(
                            text = state.error ?: state.message.orEmpty(),
                            isError = state.error != null,
                            onDismiss = onDismissMessage,
                            modifier = Modifier.padding(12.dp),
                        )
                    }

                    if (state.visible.isEmpty()) {
                        EmptyDossier(searching = state.query.isNotBlank())
                    } else {
                        LazyColumn(
                            contentPadding = PaddingValues(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(state.visible, key = { it.id }) { row ->
                                RegistrationCard(row = row, onClick = { onOpen(row) })
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RegistrationCard(row: CustomerRegistrationRow, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.size(44.dp),
            ) {
                if (row.avatarUrl != null) {
                    AsyncImage(
                        model = row.avatarUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Default.Storefront,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }
            }

            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = row.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    StatusPill(row.status)
                }

                Text(
                    text = listOfNotNull(row.code, row.address).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )

                Text(
                    text = listOfNotNull(
                        row.phone,
                        row.createdAt.take(10),
                        if (row.photoCount > 0) "${row.photoCount} ảnh" else null,
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )

                // Why it was refused: what the rep opens this list to find out.
                row.decisionNote?.takeIf { it.isNotBlank() }?.let { note ->
                    Text(
                        text = "Phản hồi: $note",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (row.status == RegistrationStatus.REJECTED) Color(0xFFC62828) else Color.Unspecified,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

// -----------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailPage(
    state: CustomerDossierUiState,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onCapture: (CustomerPhotoType) -> Unit,
    onDismissMessage: () -> Unit,
) {
    val detail = state.detail
    var confirmingDelete by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Chi tiết hồ sơ") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Quay lại")
                    }
                },
                actions = {
                    if (detail?.row?.canEdit == true) {
                        IconButton(onClick = onEdit) {
                            Icon(Icons.Default.Edit, contentDescription = "Sửa")
                        }
                        IconButton(onClick = { confirmingDelete = true }) {
                            Icon(Icons.Default.DeleteOutline, contentDescription = "Xóa")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.brand.header,
                    titleContentColor = MaterialTheme.brand.onHeader,
                    navigationIconContentColor = MaterialTheme.brand.onHeader,
                    actionIconContentColor = MaterialTheme.brand.onHeader,
                ),
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (detail == null) {
                LoadingBox()
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (state.message != null || state.error != null) {
                        item {
                            Notice(
                                text = state.error ?: state.message.orEmpty(),
                                isError = state.error != null,
                                onDismiss = onDismissMessage,
                            )
                        }
                    }

                    item { HeadCard(detail) }

                    item { SectionTitle("Ảnh hồ sơ") }

                    items(state.board.photoTypes, key = { it.id }) { type ->
                        PhotoSlot(
                            type = type,
                            photo = detail.photoFor(type.id),
                            // A settled registration is a record: the photographs
                            // stay visible and stop being replaceable.
                            editable = detail.row.canEdit,
                            capturing = state.capturingFor?.id == type.id,
                            onCapture = { onCapture(type) },
                        )
                    }

                    if (state.missingPhotos.isNotEmpty() && detail.row.canEdit) {
                        item {
                            Notice(
                                text = "Còn thiếu ảnh bắt buộc: " +
                                    state.missingPhotos.joinToString(", ") { it.name },
                                isError = false,
                                onDismiss = {},
                            )
                        }
                    }
                }
            }
        }
    }

    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text("Xóa hồ sơ?") },
            text = { Text("Hồ sơ chưa duyệt sẽ bị xóa hẳn cùng ảnh đã chụp.") },
            confirmButton = {
                TextButton(onClick = { confirmingDelete = false; onDelete() }) { Text("Xóa") }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDelete = false }) { Text("Bỏ qua") }
            },
        )
    }
}

@Composable
private fun HeadCard(detail: CustomerRegistrationDetail) {
    val row = detail.row
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    row.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                StatusPill(row.status)
            }

            Field("Mã", row.code)
            row.address?.takeIf { it.isNotBlank() }?.let { Field("Địa chỉ", it) }
            row.phone?.takeIf { it.isNotBlank() }?.let { Field("Điện thoại", it) }
            detail.contactName?.takeIf { it.isNotBlank() }?.let { Field("Người liên hệ", it) }
            row.note?.takeIf { it.isNotBlank() }?.let { Field("Ghi chú", it) }
            Field("Ngày gửi", row.createdAt.take(10))
            row.decisionNote?.takeIf { it.isNotBlank() }?.let { Field("Phản hồi", it) }

            if (!row.canEdit) {
                Text(
                    "Hồ sơ đã ${row.status.label.lowercase()} — không sửa được nữa",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}

@Composable
private fun PhotoSlot(
    type: CustomerPhotoType,
    photo: CustomerRegistrationPhoto?,
    editable: Boolean,
    capturing: Boolean,
    onCapture: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(type.name, style = MaterialTheme.typography.titleSmall)
                    Text(
                        listOfNotNull(
                            type.code,
                            if (type.isRequired) "bắt buộc" else null,
                            if (type.isAvatar) "ảnh đại diện" else null,
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }

                if (editable) {
                    IconButton(onClick = onCapture, enabled = !capturing) {
                        Icon(
                            Icons.Default.CameraAlt,
                            contentDescription = if (photo == null) "Chụp ảnh" else "Chụp lại",
                        )
                    }
                }
            }

            when {
                photo != null -> AsyncImage(
                    // The signed link, not the object name -- the bucket is
                    // private and the raw path fetches nothing.
                    model = photo.url,
                    contentDescription = type.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(4f / 3f)
                        .clip(RoundedCornerShape(8.dp)),
                )

                // The sample, when head office supplied one: what a good photo of
                // this slot looks like, which is the whole point of configuring it.
                type.sampleUrl != null -> Column {
                    Text(
                        "Ảnh mẫu",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                    AsyncImage(
                        model = type.sampleUrl,
                        contentDescription = "Ảnh mẫu",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(4f / 3f)
                            .clip(RoundedCornerShape(8.dp)),
                    )
                }

                else -> Text(
                    "Chưa có ảnh",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}

// -----------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditPage(
    state: CustomerDossierUiState,
    onBack: () -> Unit,
    onName: (String) -> Unit,
    onPhone: (String) -> Unit,
    onAddress: (String) -> Unit,
    onNote: (String) -> Unit,
    onSave: () -> Unit,
) {
    val draft = state.draft

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sửa hồ sơ") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Quay lại")
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
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            state.error?.let {
                item { Notice(text = it, isError = true, onDismiss = {}) }
            }

            item {
                OutlinedTextField(
                    value = draft.name,
                    onValueChange = onName,
                    label = { Text("Tên cửa hàng") },
                    isError = draft.nameError != null,
                    supportingText = draft.nameError?.let { { Text(it) } },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            item {
                OutlinedTextField(
                    value = draft.address,
                    onValueChange = onAddress,
                    label = { Text("Địa chỉ") },
                    isError = draft.addressError != null,
                    supportingText = draft.addressError?.let { { Text(it) } },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            item {
                OutlinedTextField(
                    value = draft.phone,
                    onValueChange = onPhone,
                    label = { Text("Điện thoại") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            item {
                OutlinedTextField(
                    value = draft.note,
                    onValueChange = onNote,
                    label = { Text("Ghi chú") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            item {
                FilledTonalButton(
                    onClick = onSave,
                    enabled = state.canSave,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Lưu")
                }
            }
        }
    }
}

// -----------------------------------------------------------------------------

@Composable
private fun StatusPill(status: RegistrationStatus) {
    val colour = when (status) {
        RegistrationStatus.PENDING -> Color(0xFFF5A202)
        RegistrationStatus.APPROVED -> Color(0xFF2E7D32)
        RegistrationStatus.REJECTED -> Color(0xFFC62828)
    }

    Surface(shape = RoundedCornerShape(6.dp), color = colour.copy(alpha = 0.16f)) {
        Text(
            status.label,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = colour,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun Field(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.width(104.dp),
        )
        Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
private fun Notice(
    text: String,
    isError: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (isError) {
            MaterialTheme.colorScheme.errorContainer
        } else {
            MaterialTheme.colorScheme.secondaryContainer
        },
        modifier = modifier.fillMaxWidth().clickable(onClick = onDismiss),
    ) {
        Text(
            text,
            modifier = Modifier.padding(12.dp),
            style = MaterialTheme.typography.bodySmall,
            color = if (isError) {
                MaterialTheme.colorScheme.onErrorContainer
            } else {
                MaterialTheme.colorScheme.onSecondaryContainer
            },
        )
    }
}

@Composable
private fun EmptyDossier(searching: Boolean) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            Icons.Default.Storefront,
            contentDescription = null,
            modifier = Modifier.size(36.dp),
            tint = MaterialTheme.colorScheme.outline,
        )
        Text(
            if (searching) "Không tìm thấy hồ sơ nào" else "Chưa gửi hồ sơ khách hàng mới nào",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
    }
}

// -----------------------------------------------------------------------------

private val SampleTypes = listOf(
    CustomerPhotoType("1", "N1", "Ảnh mặt tiền cửa hàng", isRequired = true, isAvatar = true),
    CustomerPhotoType("2", "N2", "Ảnh biển hiệu"),
)

private val SampleBoard = CustomerRegistrationBoard(
    items = listOf(
        CustomerRegistrationRow(
            id = "1",
            code = "NC0001",
            name = "Tap hoa Thanh Tam",
            phone = "0900000001",
            address = "12 Lý Thường Kiệt, Phú Hòa",
            status = RegistrationStatus.PENDING,
            createdAt = "2026-09-10T09:00:00+00:00",
            photoCount = 1,
        ),
        CustomerRegistrationRow(
            id = "2",
            code = "NC0002",
            name = "Quan cafe Ban Mai",
            address = "5 Hùng Vương, Lái Thiêu",
            status = RegistrationStatus.APPROVED,
            createdAt = "2026-09-02T09:00:00+00:00",
        ),
    ),
    counts = mapOf(RegistrationStatus.PENDING to 1, RegistrationStatus.APPROVED to 1),
    photoTypes = SampleTypes,
    total = 2,
)

@Preview(name = "Hồ sơ - danh sách", showBackground = true, heightDp = 800)
@Composable
private fun DossierListPreview() {
    MyeSalesTheme {
        ListPage(
            state = CustomerDossierUiState(loading = false, board = SampleBoard),
            onBack = {},
            onRetry = {},
            onQuery = {},
            onStatusFilter = {},
            onOpen = {},
            onRegisterNew = {},
            onDismissMessage = {},
        )
    }
}

@Preview(name = "Hồ sơ - chi tiết", showBackground = true, heightDp = 900)
@Composable
private fun DossierDetailPreview() {
    MyeSalesTheme {
        DetailPage(
            state = CustomerDossierUiState(
                loading = false,
                board = SampleBoard,
                detail = CustomerRegistrationDetail(row = SampleBoard.items[0]),
            ),
            onBack = {},
            onEdit = {},
            onDelete = {},
            onCapture = {},
            onDismissMessage = {},
        )
    }
}

@Preview(name = "Hồ sơ - sửa", showBackground = true, heightDp = 700)
@Composable
private fun DossierEditPreview() {
    MyeSalesTheme {
        EditPage(
            state = CustomerDossierUiState(
                loading = false,
                board = SampleBoard,
                detail = CustomerRegistrationDetail(row = SampleBoard.items[0]),
                draft = CustomerDraft(
                    name = "Tap hoa Thanh Tam",
                    address = "12 Lý Thường Kiệt, Phú Hòa",
                    phone = "0900000001",
                ),
            ),
            onBack = {},
            onName = {},
            onPhone = {},
            onAddress = {},
            onNote = {},
            onSave = {},
        )
    }
}
