package com.tinhcd.myesalessfa.feature.traderegis

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material.icons.filled.ViewCarousel
import androidx.compose.material.icons.filled.Workspaces
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
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tinhcd.myesalessfa.core.ui.ErrorBox
import com.tinhcd.myesalessfa.core.ui.Kpi
import com.tinhcd.myesalessfa.core.ui.KpiBar
import com.tinhcd.myesalessfa.core.ui.LoadingBox
import com.tinhcd.myesalessfa.core.ui.StatTile
import com.tinhcd.myesalessfa.core.ui.StatusPill
import com.tinhcd.myesalessfa.core.ui.theme.MyeSalesTheme
import com.tinhcd.myesalessfa.core.ui.theme.brand
import com.tinhcd.myesalessfa.domain.model.DisplayProgramOffer
import com.tinhcd.myesalessfa.domain.model.LoyaltyProgramOffer
import com.tinhcd.myesalessfa.domain.model.RegisterableCustomer
import com.tinhcd.myesalessfa.domain.model.TradeKind
import com.tinhcd.myesalessfa.domain.model.TradeRegistration
import com.tinhcd.myesalessfa.domain.model.TradeRegistrationBoard
import com.tinhcd.myesalessfa.domain.model.TradeRegistrationStatus

/**
 * Đăng ký chương trình.
 *
 * Everything this rep has signed up, and a way to sign up another outlet
 * without checking in to it first. Registering from inside a call still lives
 * in the customer hub; this is the standalone way in, which is the one the
 * legacy has and the rebuild was missing.
 */
@Composable
fun TradeRegisScreen(
    onBack: () -> Unit,
    viewModel: TradeRegisViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    when (state.page) {
        TradeRegisPage.LIST -> ListPage(
            state = state,
            onBack = onBack,
            onRetry = viewModel::load,
            onQuery = viewModel::onQuery,
            onKind = viewModel::onKind,
            onStatus = viewModel::onStatus,
            onProgram = viewModel::onProgram,
            onNew = viewModel::onOpenPicker,
            onDismissMessage = viewModel::dismissMessage,
        )

        TradeRegisPage.PICK_CUSTOMER -> PickerPage(
            state = state,
            onBack = viewModel::onBack,
            onQuery = viewModel::onPickerQuery,
            onPick = viewModel::onPickCustomer,
        )

        TradeRegisPage.OFFERS -> OffersPage(
            state = state,
            onBack = viewModel::onBack,
            onRegister = viewModel::register,
            onDismissMessage = viewModel::dismissMessage,
        )
    }
}

// -----------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ListPage(
    state: TradeRegisUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onQuery: (String) -> Unit,
    onKind: (TradeKind?) -> Unit,
    onStatus: (TradeRegistrationStatus?) -> Unit,
    onProgram: (String?) -> Unit,
    onNew: () -> Unit,
    onDismissMessage: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Đăng ký chương trình") },
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
            FloatingActionButton(onClick = onNew) {
                Icon(Icons.Default.Add, contentDescription = "Đăng ký cho khách hàng")
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                state.loading && state.board.items.isEmpty() -> LoadingBox()
                state.error != null && state.board.items.isEmpty() ->
                    ErrorBox(state.error, onRetry = onRetry)

                else -> LazyColumn(
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    item { SlotCard(state.board) }

                    item {
                        OutlinedTextField(
                            value = state.query,
                            onValueChange = onQuery,
                            label = { Text("Tìm khách hàng hoặc chương trình") },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            FilterChip(
                                selected = state.kindFilter == null,
                                onClick = { onKind(null) },
                                label = { Text("Tất cả ${state.board.total}") },
                            )
                            TradeKind.entries.forEach { kind ->
                                FilterChip(
                                    selected = state.kindFilter == kind,
                                    onClick = { onKind(kind) },
                                    label = { Text("${kind.label} ${state.board.count(kind)}") },
                                )
                            }
                            state.board.presentStatuses.forEach { status ->
                                FilterChip(
                                    selected = state.statusFilter == status,
                                    onClick = { onStatus(status) },
                                    label = { Text("${status.label} ${state.board.count(status)}") },
                                )
                            }
                        }
                    }

                    // Programmes of the selected kind only, so a chip from the
                    // other tab cannot filter the list to nothing.
                    val programs = state.board.programsOf(state.kindFilter)
                    if (programs.size > 1) {
                        item {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                programs.forEach { program ->
                                    FilterChip(
                                        selected = state.programFilter == program.id,
                                        onClick = { onProgram(program.id) },
                                        label = { Text(program.name) },
                                    )
                                }
                            }
                        }
                    }

                    if (state.message != null || state.error != null) {
                        item {
                            Notice(
                                text = state.error ?: state.message.orEmpty(),
                                isError = state.error != null,
                                onDismiss = onDismissMessage,
                            )
                        }
                    }

                    if (state.visible.isEmpty()) {
                        item { EmptyBox("Chưa đăng ký chương trình nào") }
                    } else {
                        items(state.visible, key = { it.id }) { row -> RegistrationCard(row) }
                    }
                }
            }
        }
    }
}

/** What head office gave this rep, against what they have spent. */
@Composable
private fun SlotCard(board: TradeRegistrationBoard) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Suất đăng ký đã dùng",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "${board.slotsUsed} / ${board.slotsTotal}",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )

            // No ceiling at all draws no bar: nought per cent of nothing is not
            // a figure, and an empty bar would read as "you have used none of
            // an allocation you were given".
            board.slotProgress?.let { KpiBar(progress = it, ramp = Kpi.Violet) }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile(
                    label = "Trưng bày",
                    value = board.count(TradeKind.DISPLAY).toString(),
                    colors = Kpi.Violet,
                    modifier = Modifier.weight(1f),
                )
                StatTile(
                    label = "Tích lũy",
                    value = board.count(TradeKind.LOYALTY).toString(),
                    colors = Kpi.Green,
                    modifier = Modifier.weight(1f),
                )
                StatTile(
                    label = "Còn lại",
                    value = board.slotsLeft.toString(),
                    colors = Kpi.Blue,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun RegistrationCard(row: TradeRegistration) {
    val colour = kindColour(row.kind)

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    shape = CircleShape,
                    color = colour.copy(alpha = 0.14f),
                    modifier = Modifier.size(34.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            if (row.kind == TradeKind.DISPLAY) Icons.Default.ViewCarousel
                            else Icons.Default.Workspaces,
                            contentDescription = null,
                            modifier = Modifier.size(17.dp),
                            tint = colour,
                        )
                    }
                }

                Column(Modifier.weight(1f)) {
                    Text(
                        row.customerName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        row.programName,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                StatusPill(row.status.label, statusColour(row.status))
            }

            Text(
                listOfNotNull(
                    row.customerCode,
                    row.levelName,
                    row.programCode,
                    row.registeredAt,
                    if (row.portion > 1) "${row.portion} suất" else null,
                ).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )

            row.reason?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall)
            }

            // The supervisor's word on it, red when it is why it was refused.
            row.decisionNote?.takeIf { it.isNotBlank() }?.let { note ->
                Text(
                    "Phản hồi: $note",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (row.status == TradeRegistrationStatus.REJECTED) Color(0xFFC62828) else Color.Unspecified,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

// -----------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PickerPage(
    state: TradeRegisUiState,
    onBack: () -> Unit,
    onQuery: (String) -> Unit,
    onPick: (RegisterableCustomer) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Chọn khách hàng") },
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
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = state.pickerQuery,
                onValueChange = onQuery,
                label = { Text("Tìm theo tên hoặc mã") },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().padding(12.dp),
            )

            if (state.pickerLoading) {
                LoadingBox()
            } else if (state.visibleCustomers.isEmpty()) {
                EmptyBox("Không tìm thấy khách hàng nào")
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(state.visibleCustomers, key = { it.id }) { customer ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPick(customer) },
                            shape = RoundedCornerShape(14.dp),
                            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                        ) {
                            Row(
                                Modifier.fillMaxWidth().padding(14.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Surface(
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.secondaryContainer,
                                    modifier = Modifier.size(36.dp),
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            Icons.Default.Storefront,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp),
                                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                        )
                                    }
                                }
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        customer.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium,
                                    )
                                    Text(
                                        listOfNotNull(customer.code, customer.address)
                                            .filter { it.isNotBlank() }
                                            .joinToString(" · "),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// -----------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OffersPage(
    state: TradeRegisUiState,
    onBack: () -> Unit,
    onRegister: (TradeKind, String, String) -> Unit,
    onDismissMessage: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Chương trình mở")
                        state.chosen?.let {
                            Text(it.name, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                },
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
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                state.offersLoading -> LoadingBox()

                state.offers.isEmpty -> EmptyBox(
                    "Khách hàng này không còn chương trình nào để đăng ký",
                )

                else -> LazyColumn(
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    state.error?.let {
                        item { Notice(text = it, isError = true, onDismiss = onDismissMessage) }
                    }

                    if (state.offers.display.isNotEmpty()) {
                        item { SectionTitle("Trưng bày") }
                        items(state.offers.display, key = { it.programId }) { offer ->
                            DisplayOfferCard(
                                offer = offer,
                                working = state.working,
                                onRegister = { levelId ->
                                    onRegister(TradeKind.DISPLAY, offer.programId, levelId)
                                },
                            )
                        }
                    }

                    if (state.offers.loyalty.isNotEmpty()) {
                        item { SectionTitle("Tích lũy") }
                        items(state.offers.loyalty, key = { it.programId }) { offer ->
                            LoyaltyOfferCard(
                                offer = offer,
                                working = state.working,
                                onRegister = { levelId ->
                                    onRegister(TradeKind.LOYALTY, offer.programId, levelId)
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DisplayOfferCard(
    offer: DisplayProgramOffer,
    working: Boolean,
    onRegister: (String) -> Unit,
) {
    OfferCard(
        name = offer.programName,
        code = offer.programCode,
        specification = offer.specification,
        window = windowLabel(offer.regisFromDate, offer.regisToDate),
        colour = kindColour(TradeKind.DISPLAY),
    ) {
        offer.levels.forEach { level ->
            LevelRow(
                name = level.levelName,
                detail = listOfNotNull(
                    "${level.requiredFaces} mặt",
                    level.slotsLeft?.let { "còn $it suất" },
                ).joinToString(" · "),
                enabled = level.available && !working,
                onRegister = { onRegister(level.levelId) },
            )
        }
    }
}

@Composable
private fun LoyaltyOfferCard(
    offer: LoyaltyProgramOffer,
    working: Boolean,
    onRegister: (String) -> Unit,
) {
    OfferCard(
        name = offer.programName,
        code = offer.programCode,
        specification = offer.specification,
        window = windowLabel(offer.regisFromDate, offer.regisToDate),
        colour = kindColour(TradeKind.LOYALTY),
    ) {
        offer.levels.forEach { level ->
            LevelRow(
                name = level.levelName,
                detail = listOfNotNull(
                    level.slotsLeft?.let { "còn $it suất" },
                ).joinToString(" · "),
                enabled = level.available && !working,
                onRegister = { onRegister(level.levelId) },
            )
        }
    }
}

@Composable
private fun OfferCard(
    name: String,
    code: String,
    specification: String?,
    window: String?,
    colour: Color,
    levels: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = colour,
            )
            Text(
                listOfNotNull(code, window).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
            specification?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
            levels()
        }
    }
}

@Composable
private fun LevelRow(
    name: String,
    detail: String,
    enabled: Boolean,
    onRegister: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyMedium)
            if (detail.isNotBlank()) {
                Text(
                    detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
        FilledTonalButton(onClick = onRegister, enabled = enabled) {
            // A level with no slots left is shown and refused rather than
            // hidden: the rep needs to know it exists and is full.
            Text(if (enabled) "Đăng ký" else "Hết suất")
        }
    }
}

// -----------------------------------------------------------------------------

private fun kindColour(kind: TradeKind): Color = when (kind) {
    TradeKind.DISPLAY -> Color(0xFF5C00D4)
    TradeKind.LOYALTY -> Color(0xFF04A489)
}

private fun statusColour(status: TradeRegistrationStatus): Color = when (status) {
    TradeRegistrationStatus.PENDING -> Color(0xFFF5A202)
    TradeRegistrationStatus.APPROVED -> Color(0xFF2E7D32)
    TradeRegistrationStatus.REJECTED -> Color(0xFFC62828)
}

private fun windowLabel(from: String?, to: String?): String? = when {
    from != null && to != null -> "$from - $to"
    from != null -> "từ $from"
    to != null -> "đến $to"
    else -> null
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
private fun Notice(text: String, isError: Boolean, onDismiss: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (isError) {
            MaterialTheme.colorScheme.errorContainer
        } else {
            MaterialTheme.colorScheme.secondaryContainer
        },
        modifier = Modifier.fillMaxWidth().clickable(onClick = onDismiss),
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
private fun EmptyBox(text: String) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp, horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            Icons.Default.Workspaces,
            contentDescription = null,
            modifier = Modifier.size(38.dp),
            tint = MaterialTheme.colorScheme.outlineVariant,
        )
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

// -----------------------------------------------------------------------------

private val SampleBoard = TradeRegistrationBoard(
    items = listOf(
        TradeRegistration(
            id = "1",
            kind = TradeKind.LOYALTY,
            registeredAt = "2026-09-10",
            status = TradeRegistrationStatus.PENDING,
            portion = 1,
            customerId = "c1",
            customerCode = "KH006",
            customerName = "Tap hoa Tuoi Tre",
            programId = "p1",
            programCode = "TL-Q3",
            programName = "Tích lũy nước giải khát quý 3",
            levelName = "Bậc 1 - từ 5 triệu",
        ),
        TradeRegistration(
            id = "2",
            kind = TradeKind.DISPLAY,
            registeredAt = "2026-09-02",
            status = TradeRegistrationStatus.APPROVED,
            portion = 2,
            customerId = "c2",
            customerCode = "KH001",
            customerName = "Tap hoa Minh Anh",
            programId = "p2",
            programCode = "TB2609",
            programName = "Trưng bày nước giải khát quý 3",
            levelName = "Mức 2",
        ),
    ),
    counts = mapOf(
        TradeRegistrationStatus.PENDING to 1,
        TradeRegistrationStatus.APPROVED to 1,
    ),
    kindCounts = mapOf(TradeKind.DISPLAY to 1, TradeKind.LOYALTY to 1),
    slotsTotal = 13,
    slotsUsed = 3,
    total = 2,
)

@Preview(name = "Đăng ký chương trình", showBackground = true, heightDp = 900)
@Composable
private fun TradeRegisPreview() {
    MyeSalesTheme {
        ListPage(
            state = TradeRegisUiState(loading = false, board = SampleBoard),
            onBack = {},
            onRetry = {},
            onQuery = {},
            onKind = {},
            onStatus = {},
            onProgram = {},
            onNew = {},
            onDismissMessage = {},
        )
    }
}
