package com.tinhcd.myesalessfa.feature.incall.steps

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tinhcd.myesalessfa.domain.DataResult
import com.tinhcd.myesalessfa.domain.getOrNull
import com.tinhcd.myesalessfa.domain.model.DraftStockCount
import com.tinhcd.myesalessfa.domain.model.PricedProduct
import com.tinhcd.myesalessfa.domain.model.PricedUnit
import com.tinhcd.myesalessfa.domain.model.StockCountLine
import com.tinhcd.myesalessfa.domain.model.StockScope
import com.tinhcd.myesalessfa.domain.model.browse
import com.tinhcd.myesalessfa.domain.model.categoryNames
import com.tinhcd.myesalessfa.domain.model.inScope
import com.tinhcd.myesalessfa.domain.repository.StockRepository
import com.tinhcd.myesalessfa.domain.usecase.GetVisitCatalogueUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class StockCountUiState(
    val loading: Boolean = true,
    val customerName: String = "",
    val query: String = "",
    val catalogue: List<PricedProduct> = emptyList(),
    /** Product id -> base-unit total at the customer's previous count. */
    val previous: Map<String, Int> = emptyMap(),
    /** True when the previous count could not be fetched, so "last time" is unknown. */
    val previousUnavailable: Boolean = false,
    val chosenUnit: Map<String, String> = emptyMap(),
    val count: DraftStockCount = DraftStockCount(visitId = "", customerId = ""),
    /** Which slice of the catalogue the sheet is showing. */
    val scope: StockScope = StockScope.PURCHASED,
    /**
     * Products this outlet has bought in the last three months, which is what the
     * legacy count sheet is built from. Empty when the history could not be read
     * or the rep has never sold here; [StockScope.PURCHASED] then falls through to
     * the whole catalogue rather than showing nothing.
     */
    val purchased: Set<String> = emptySet(),
    /** Empty means every category, which is how the legacy filter starts. */
    val categories: Set<String> = emptySet(),
    val submitting: Boolean = false,
    val error: String? = null,
    val finished: Boolean = false,
) {
    val visible: List<PricedProduct>
        get() = catalogue
            .inScope(scope, purchased, count.mustStock.keys)
            .browse(query = query, categories = categories)

    val allCategories: List<String> get() = catalogue.categoryNames()

    /** How many products each scope would show, for the chips' counts. */
    fun sizeOf(scope: StockScope): Int =
        catalogue.inScope(scope, purchased, count.mustStock.keys).size

    /**
     * True when leaving now would throw away work. Nothing is written until the
     * rep presses submit, so a stray back press costs the whole sheet — the app
     * this replaces asks before letting that happen and this one did not.
     */
    val hasUnsavedWork: Boolean get() = count.lines.isNotEmpty() && !finished

    fun parFor(product: PricedProduct): Int? = count.mustStock[product.product.id]

    fun unitFor(product: PricedProduct): PricedUnit {
        val code = chosenUnit[product.product.id] ?: return product.defaultUnit
        return product.units.firstOrNull { it.unit.uomCode == code } ?: product.defaultUnit
    }
}

/**
 * Backs the `stock_outlet` step.
 *
 * Counting is only useful next to the last count, so the previous figures are
 * fetched up front and shown per product while the rep works. The server fills
 * them again on submit from its own data — the device's copy is for the rep's
 * eyes, and a failed fetch must not put a wrong "last time" on the screen.
 */
@HiltViewModel
class StockCountViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val getVisitCatalogue: GetVisitCatalogueUseCase,
    private val stockRepository: StockRepository,
) : ViewModel() {

    private val visitId: String = checkNotNull(savedStateHandle["visitId"])
    private val customerId: String = checkNotNull(savedStateHandle["customerId"])

    private val _state = MutableStateFlow(StockCountUiState())
    val state: StateFlow<StockCountUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            val previous = stockRepository.previousCount(customerId, visitId)

            // What this outlet actually buys. Like the previous figures, losing it
            // costs a narrowing and not the ability to count — the scope chip then
            // falls through to the whole catalogue.
            val purchased = stockRepository.purchasedProducts(customerId)
                .getOrNull()
                .orEmpty()

            // The rep counts what they can sell, so the same priced catalogue and the
            // same par levels the order step works from. A product with no price for
            // this outlet is one they cannot order, which makes counting it busywork.
            when (val visit = getVisitCatalogue(customerId)) {
                is DataResult.Success -> _state.update {
                    it.copy(
                        loading = false,
                        customerName = visit.data.customer?.name.orEmpty(),
                        catalogue = visit.data.catalogue,
                        previous = previous.getOrNull().orEmpty(),
                        // Not an error the rep has to act on: they can still
                        // count, they just do it without the comparison.
                        previousUnavailable = previous is DataResult.Failure,
                        purchased = purchased,
                        count = DraftStockCount(
                            visitId = visitId,
                            customerId = customerId,
                            mustStock = visit.data.mustStock,
                        ),
                    )
                }

                is DataResult.Failure -> _state.update {
                    it.copy(loading = false, error = "Không tải được danh mục sản phẩm")
                }
            }
        }
    }

    fun onQueryChange(value: String) = _state.update { it.copy(query = value) }

    fun onScopeChange(scope: StockScope) = _state.update { it.copy(scope = scope) }

    fun onCategoryToggle(name: String) = _state.update {
        val next = if (name in it.categories) it.categories - name else it.categories + name
        it.copy(categories = next)
    }

    fun clearCategories() = _state.update { it.copy(categories = emptySet()) }

    /** Moves any entry onto the new unit rather than leaving one behind. */
    fun onUnitChange(product: PricedProduct, uomCode: String) {
        val current = _state.value
        val previousUnit = current.unitFor(product)
        if (previousUnit.unit.uomCode == uomCode) return

        val existing = current.count.lineFor(product.product.id, previousUnit.unit.uomCode)
        val next = product.units.firstOrNull { it.unit.uomCode == uomCode } ?: return

        var count = current.count.withoutLine(product.product.id, previousUnit.unit.uomCode)
        if (existing != null) count = count.withLine(lineFor(product, next, existing.qty))

        _state.update {
            it.copy(
                chosenUnit = it.chosenUnit + (product.product.id to uomCode),
                count = count,
                error = null,
            )
        }
    }

    /**
     * Records a quantity, including zero. Zero is an out-of-stock report and is
     * kept; [onClearProduct] is how the rep says they did not check at all.
     */
    fun onQtyChange(product: PricedProduct, qty: Int) {
        val current = _state.value
        val unit = current.unitFor(product)
        val line = lineFor(product, unit, qty.coerceAtLeast(0))
        _state.update { it.copy(count = it.count.withLine(line), error = null) }
    }

    fun onClearProduct(product: PricedProduct) {
        val current = _state.value
        val unit = current.unitFor(product)
        _state.update {
            it.copy(
                count = it.count.withoutLine(product.product.id, unit.unit.uomCode),
                error = null,
            )
        }
    }

    fun submit() {
        val current = _state.value
        if (current.submitting || !current.count.canSubmit) return

        _state.update { it.copy(submitting = true, error = null) }
        viewModelScope.launch {
            when (stockRepository.submit(current.count)) {
                is DataResult.Success -> _state.update {
                    it.copy(submitting = false, finished = true)
                }

                is DataResult.Failure -> _state.update {
                    it.copy(submitting = false, error = "Không lưu được phiếu kiểm tồn")
                }
            }
        }
    }

    private fun lineFor(product: PricedProduct, unit: PricedUnit, qty: Int) = StockCountLine(
        productId = product.product.id,
        productCode = product.product.code,
        productName = product.product.name,
        uomCode = unit.unit.uomCode,
        uomName = unit.unit.uomName,
        conversionRate = unit.unit.conversionRate,
        qty = qty,
        prevBaseQty = _state.value.previous[product.product.id] ?: 0,
        mslMinBaseQty = _state.value.count.mustStock[product.product.id],
    )
}
