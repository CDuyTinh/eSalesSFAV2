package com.tinhcd.myesalessfa.feature.incall.steps

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tinhcd.myesalessfa.domain.DataResult
import com.tinhcd.myesalessfa.domain.model.DraftStockCount
import com.tinhcd.myesalessfa.domain.model.PricedProduct
import com.tinhcd.myesalessfa.domain.model.PricedUnit
import com.tinhcd.myesalessfa.domain.model.StockCountLine
import com.tinhcd.myesalessfa.domain.repository.CatalogRepository
import com.tinhcd.myesalessfa.domain.repository.RouteRepository
import com.tinhcd.myesalessfa.domain.repository.StockRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
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
    /** When on, the list shows only the SKUs the outlet is obliged to stock. */
    val mustStockOnly: Boolean = false,
    val submitting: Boolean = false,
    val error: String? = null,
    val finished: Boolean = false,
) {
    val visible: List<PricedProduct>
        get() {
            val needle = query.trim().lowercase()
            return catalogue.filter { priced ->
                val matchesQuery = needle.isBlank() ||
                    priced.product.name.lowercase().contains(needle) ||
                    priced.product.code.lowercase().contains(needle)
                val matchesFilter = !mustStockOnly ||
                    priced.product.id in count.mustStock
                matchesQuery && matchesFilter
            }
        }

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
    private val catalogRepository: CatalogRepository,
    private val stockRepository: StockRepository,
    private val routeRepository: RouteRepository,
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
            val stop = (routeRepository.getStop(customerId, LocalDate.now()) as? DataResult.Success)
                ?.data

            // The rep counts what they can sell, so the same priced catalogue the
            // order step uses. A product with no price for this outlet is one they
            // cannot order, which makes counting it busywork.
            val catalogue = catalogRepository.catalogue(stop?.customer?.classId, LocalDate.now())

            val previous = stockRepository.previousCount(customerId, visitId)

            // Which SKUs this outlet owes comes from its channel and shop type,
            // resolved against the cached lists — so it works with no signal, which
            // is the situation this screen exists for.
            val mustStock = catalogRepository.mustStock(
                channelId = stop?.customer?.channelId,
                shopTypeId = stop?.customer?.shopTypeId,
                on = LocalDate.now(),
            )

            when (catalogue) {
                is DataResult.Success -> _state.update {
                    it.copy(
                        loading = false,
                        customerName = stop?.customer?.name.orEmpty(),
                        catalogue = catalogue.data,
                        previous = (previous as? DataResult.Success)?.data.orEmpty(),
                        // Not an error the rep has to act on: they can still
                        // count, they just do it without the comparison.
                        previousUnavailable = previous is DataResult.Failure,
                        count = DraftStockCount(
                            visitId = visitId,
                            customerId = customerId,
                            mustStock = (mustStock as? DataResult.Success)?.data.orEmpty(),
                        ),
                    )
                }

                is DataResult.Failure -> _state.update {
                    it.copy(loading = false, error = "Khong tai duoc danh muc san pham")
                }
            }
        }
    }

    fun onQueryChange(value: String) = _state.update { it.copy(query = value) }

    fun onMustStockOnlyChange(enabled: Boolean) =
        _state.update { it.copy(mustStockOnly = enabled) }

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
                    it.copy(submitting = false, error = "Khong luu duoc phieu kiem ton")
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
