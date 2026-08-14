package com.tinhcd.myesalessfa.feature.incall.steps

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tinhcd.myesalessfa.domain.DataResult
import com.tinhcd.myesalessfa.domain.model.DraftOrder
import com.tinhcd.myesalessfa.domain.model.OrderLine
import com.tinhcd.myesalessfa.domain.model.PricedProduct
import com.tinhcd.myesalessfa.domain.model.PricedUnit
import com.tinhcd.myesalessfa.domain.repository.CatalogRepository
import com.tinhcd.myesalessfa.domain.repository.OrderRepository
import com.tinhcd.myesalessfa.domain.repository.RouteRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

data class TakeOrderUiState(
    val loading: Boolean = true,
    val customerName: String = "",
    val query: String = "",
    val catalogue: List<PricedProduct> = emptyList(),
    /** Which unit the rep has picked per product; absent means the default. */
    val chosenUnit: Map<String, String> = emptyMap(),
    val order: DraftOrder = DraftOrder(visitId = "", customerId = ""),
    val submitting: Boolean = false,
    val error: String? = null,
    val finished: Boolean = false,
) {
    val visible: List<PricedProduct>
        get() = if (query.isBlank()) {
            catalogue
        } else {
            val needle = query.trim().lowercase()
            catalogue.filter {
                it.product.name.lowercase().contains(needle) ||
                    it.product.code.lowercase().contains(needle)
            }
        }

    fun unitFor(product: PricedProduct): PricedUnit {
        val code = chosenUnit[product.product.id] ?: return product.defaultUnit
        return product.units.firstOrNull { it.unit.uomCode == code } ?: product.defaultUnit
    }
}

/**
 * Backs the `take_order` step.
 *
 * The order is totalled here so the rep can read a figure to the customer before
 * anything has been sent, but the figures are advisory: `submit_order` re-prices
 * the order from the same effective-dated catalogue and its answer is the one
 * that is booked. The device's total travels with the order so the two can be
 * compared after the fact.
 */
@HiltViewModel
class TakeOrderViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val catalogRepository: CatalogRepository,
    private val orderRepository: OrderRepository,
    private val routeRepository: RouteRepository,
) : ViewModel() {

    private val visitId: String = checkNotNull(savedStateHandle["visitId"])
    private val customerId: String = checkNotNull(savedStateHandle["customerId"])

    private val _state = MutableStateFlow(TakeOrderUiState())
    val state: StateFlow<TakeOrderUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            val stop = (routeRepository.getStop(customerId, LocalDate.now()) as? DataResult.Success)
                ?.data

            // The customer's class decides the price list that applies, so it is
            // needed before anything can be shown with a figure next to it.
            when (val result = catalogRepository.catalogue(stop?.customer?.classId, LocalDate.now())) {
                is DataResult.Success -> _state.update {
                    it.copy(
                        loading = false,
                        customerName = stop?.customer?.name.orEmpty(),
                        catalogue = result.data,
                        order = DraftOrder(visitId = visitId, customerId = customerId),
                    )
                }

                is DataResult.Failure -> _state.update {
                    it.copy(loading = false, error = "Khong tai duoc danh muc san pham")
                }
            }
        }
    }

    fun onQueryChange(value: String) = _state.update { it.copy(query = value) }

    /**
     * Switching unit moves the quantity onto the new unit rather than leaving a
     * line behind on the old one — 5 cases becoming 5 cases *and* 5 pieces is
     * never what the rep meant.
     */
    fun onUnitChange(product: PricedProduct, uomCode: String) {
        val current = _state.value
        val previous = current.unitFor(product)
        if (previous.unit.uomCode == uomCode) return

        val qty = current.order.quantityOf(product.product.id, previous.unit.uomCode)
        val next = product.units.firstOrNull { it.unit.uomCode == uomCode } ?: return

        var order = current.order.withoutLine(product.product.id, previous.unit.uomCode)
        if (qty > 0) order = order.withLine(lineFor(product, next, qty))

        _state.update {
            it.copy(
                chosenUnit = it.chosenUnit + (product.product.id to uomCode),
                order = order,
                error = null,
            )
        }
    }

    fun onQtyChange(product: PricedProduct, qty: Int) {
        val current = _state.value
        val unit = current.unitFor(product)
        val clamped = qty.coerceAtLeast(0)

        // Zero is how a line is removed: the server rejects a zero-quantity line,
        // and an order carrying one would be a line the customer did not agree to.
        val order = if (clamped == 0) {
            current.order.withoutLine(product.product.id, unit.unit.uomCode)
        } else {
            current.order.withLine(lineFor(product, unit, clamped))
        }

        _state.update { it.copy(order = order, error = null) }
    }

    fun submit() {
        val current = _state.value
        if (current.submitting || !current.order.canSubmit) return

        _state.update { it.copy(submitting = true, error = null) }
        viewModelScope.launch {
            // QUEUED is not surfaced here. The order is recorded either way, and
            // the in-call and route screens both show how much is still waiting.
            when (orderRepository.submit(current.order)) {
                is DataResult.Success -> _state.update {
                    it.copy(submitting = false, finished = true)
                }

                is DataResult.Failure -> _state.update {
                    it.copy(submitting = false, error = "Khong luu duoc don hang")
                }
            }
        }
    }

    private fun lineFor(product: PricedProduct, unit: PricedUnit, qty: Int) = OrderLine(
        productId = product.product.id,
        productCode = product.product.code,
        productName = product.product.name,
        uomCode = unit.unit.uomCode,
        uomName = unit.unit.uomName,
        conversionRate = unit.unit.conversionRate,
        qty = qty,
        unitPrice = unit.price,
        vatBasisPoints = product.product.vatBasisPoints,
    )
}
