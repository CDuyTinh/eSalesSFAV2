package com.tinhcd.myesalessfa.feature.promotion

import androidx.compose.foundation.clickable
import com.tinhcd.myesalessfa.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.tinhcd.myesalessfa.core.ui.ErrorBox
import com.tinhcd.myesalessfa.core.ui.HeaderShape
import com.tinhcd.myesalessfa.core.ui.SkeletonList
import com.tinhcd.myesalessfa.core.ui.formatDong
import com.tinhcd.myesalessfa.core.ui.theme.brand
import com.tinhcd.myesalessfa.domain.DataResult
import com.tinhcd.myesalessfa.domain.model.CatalogueLevel
import com.tinhcd.myesalessfa.domain.model.CataloguePromotion
import com.tinhcd.myesalessfa.domain.model.PromotionReward
import com.tinhcd.myesalessfa.domain.model.PromotionScope
import com.tinhcd.myesalessfa.domain.repository.PromotionCatalogueRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.format.DateTimeFormatter
import javax.inject.Inject

data class PromotionCatalogueUiState(
    val loading: Boolean = true,
    val promotions: List<CataloguePromotion> = emptyList(),
    val expanded: Set<String> = emptySet(),
    val error: String? = null,
)

@HiltViewModel
class PromotionCatalogueViewModel @Inject constructor(
    private val repository: PromotionCatalogueRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(PromotionCatalogueUiState())
    val state: StateFlow<PromotionCatalogueUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            when (val r = repository.running()) {
                is DataResult.Success -> _state.update { it.copy(loading = false, promotions = r.data) }
                is DataResult.Failure -> _state.update {
                    it.copy(loading = false, error = "Không tải được chương trình khuyến mãi")
                }
            }
        }
    }

    fun toggle(sequenceId: String) = _state.update {
        it.copy(expanded = if (sequenceId in it.expanded) it.expanded - sequenceId else it.expanded + sequenceId)
    }
}

private val dayFormat = DateTimeFormatter.ofPattern("dd/MM/yyyy")

/**
 * Chương trình khuyến mãi (the legacy's Sản phẩm trọng tâm → Chương trình KM):
 * what is running for the distributor, read before the round. A card names the
 * promotion and its dates; opened, it shows the products it counts, its levels,
 * its gifts and what the route's budget has left.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PromotionCatalogueScreen(
    onBack: () -> Unit,
    viewModel: PromotionCatalogueViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                modifier = Modifier.clip(HeaderShape),
                title = { Text(stringResource(R.string.sfa_106)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
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
                state.loading -> SkeletonList(count = 5, lines = 3)
                state.error != null -> ErrorBox(state.error!!, onRetry = viewModel::load)
                state.promotions.isEmpty() -> ErrorBox(stringResource(R.string.sfa_107))
                else -> LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item {
                        Text(
                            stringResource(R.string.sft_76, state.promotions.size),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    items(state.promotions, key = { it.sequenceId }) { promo ->
                        PromotionItem(
                            promo = promo,
                            expanded = promo.sequenceId in state.expanded,
                            onToggle = { viewModel.toggle(promo.sequenceId) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PromotionItem(promo: CataloguePromotion, expanded: Boolean, onToggle: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(promo.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                    Text(
                        "${promo.programCode} · ${promo.programName}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) stringResource(R.string.sfb_47) else stringResource(R.string.sfb_48),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                listOfNotNull(
                    kindLabel(promo),
                    period(promo),
                    promo.proposalName?.let { stringResource(R.string.sft_77, it) },
                    stringResource(R.string.sfb_167).takeIf { promo.targeted },
                ).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )

            if (expanded) {
                if (promo.items.isNotEmpty()) {
                    Section(stringResource(R.string.sfa_108))
                    promo.items.forEach { Line("${it.productCode} – ${it.productName}", it.uomName) }
                }
                Section(stringResource(R.string.sfa_109))
                promo.levels.forEach { Line(it.name, levelText(promo, it)) }
                if (promo.gifts.isNotEmpty()) {
                    Section(stringResource(R.string.sfa_110))
                    promo.gifts.forEach { Line(it.productName, "${it.qty} ${it.uomName}") }
                }
                promo.budget?.let { b ->
                    Section(stringResource(R.string.sft_78, b.name))
                    Line(stringResource(R.string.sfa_111), b.allocated?.let { budgetFigure(b.counts, it) })
                    Line(stringResource(R.string.sfa_112), b.spent?.let { budgetFigure(b.counts, it) })
                    Line(stringResource(R.string.sfa_113), b.remaining?.let { budgetFigure(b.counts, it) })
                }
            }
        }
    }
}

@Composable
private fun Section(title: String) {
    HorizontalDivider(Modifier.padding(top = 6.dp))
    Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun Line(left: String, right: String?) {
    Row(Modifier.fillMaxWidth()) {
        Text(left, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        Text(right ?: "—", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun kindLabel(p: CataloguePromotion): String {
    val scope = when (p.scope) {
        PromotionScope.ORDER -> stringResource(R.string.sfc_39)
        PromotionScope.GROUP -> if (p.isBundle) "Combo" else stringResource(R.string.sfc_40)
        else -> if (p.isBundle) "Combo" else stringResource(R.string.sfc_41)
    }
    val reward = when (p.reward) {
        PromotionReward.PERCENT -> stringResource(R.string.sfc_42)
        PromotionReward.FREE_ITEM -> stringResource(R.string.sfc_43)
        PromotionReward.AMOUNT_OR_ITEM -> stringResource(R.string.sfc_44)
        else -> stringResource(R.string.sfc_45)
    }
    return "$scope, $reward"
}

@Composable
private fun period(p: CataloguePromotion): String? {
    val from = p.fromDate?.format(dayFormat) ?: return null
    return p.toDate?.let { "$from – ${it.format(dayFormat)}" } ?: stringResource(R.string.sfc_76, from)
}

/** "Mua 10 Thùng → tặng / giảm …": the threshold and what one lot of it pays. */
@Composable
private fun levelText(p: CataloguePromotion, l: CatalogueLevel): String {
    val threshold = when {
        p.isBundle -> "${l.breakQty.trim()} combo"
        p.byQty -> stringResource(R.string.sfc_76, l.breakQty.trim())
        else -> stringResource(R.string.sfc_76, formatDong(l.breakAmt.toLong()))
    }
    val pays = when (p.reward) {
        PromotionReward.PERCENT -> stringResource(R.string.sfc_77, l.discAmt.trim())
        PromotionReward.FREE_ITEM -> stringResource(R.string.sfc_43)
        PromotionReward.AMOUNT_OR_ITEM -> stringResource(R.string.sfc_78, formatDong(l.discAmt.toLong()))
        else -> stringResource(R.string.sfc_79, formatDong(l.discAmt.toLong()))
    }
    val caps = listOfNotNull(
        l.maxLot.takeIf { it > 0 }?.let { stringResource(R.string.sfc_80, it) },
        l.customerMaxLot?.let { stringResource(R.string.sfc_81, it) },
    )
    return (listOf(threshold, pays) + caps).joinToString(" · ")
}

private fun Double.trim(): String = toBigDecimal().stripTrailingZeros().toPlainString()

@Composable
private fun budgetFigure(counts: String, value: Double): String = when (counts) {
    "lots" -> stringResource(R.string.sft_85, value.trim())
    "free_item" -> stringResource(R.string.sfc_82, value.trim())
    else -> formatDong(value.toLong())
}
