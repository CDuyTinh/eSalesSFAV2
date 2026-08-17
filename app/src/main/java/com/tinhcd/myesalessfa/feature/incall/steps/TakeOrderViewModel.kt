package com.tinhcd.myesalessfa.feature.incall.steps

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tinhcd.myesalessfa.domain.DataResult
import com.tinhcd.myesalessfa.domain.model.Customer
import com.tinhcd.myesalessfa.domain.model.DraftOrder
import com.tinhcd.myesalessfa.domain.model.OrderLine
import com.tinhcd.myesalessfa.domain.model.OrderSuggestion
import com.tinhcd.myesalessfa.domain.model.PricedProduct
import com.tinhcd.myesalessfa.domain.model.PricedUnit
import com.tinhcd.myesalessfa.domain.model.orderSuggestions
import com.tinhcd.myesalessfa.domain.repository.CatalogRepository
import com.tinhcd.myesalessfa.domain.repository.OrderRepository
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

data class TakeOrderUiState(
    val loading: Boolean = true,
    val customerName: String = "",
    val query: String = "",
    val catalogue: List<PricedProduct> = emptyList(),
    /** Which unit the rep has picked per product; absent means the default. */
    val chosenUnit: Map<String, String> = emptyMap(),
    val order: DraftOrder = DraftOrder(visitId = "", customerId = ""),
    /**
     * Replenishment for must-stock SKUs the count found below par. Offered, never
     * applied on arrival — see [TakeOrderViewModel.applySuggestions].
     */
    val suggestions: List<OrderSuggestion> = emptyList(),
    val suggestionsApplied: Boolean = false,
    val submitting: Boolean = false,
    val error: String? = null,
    val finished: Boolean = false,
) {
    fun suggestionFor(productId: String): OrderSuggestion? =
        suggestions.firstOrNull { it.productId == productId }

    /**
     * Every line on this product, biggest unit first.
     *
     * The row used to show only the line matching the unit picker, which was fine
     * while one product meant one line. Applying a split suggestion writes two — a
     * case and a few loose pieces — and the second would have been invisible and
     * un-editable while still counting towards the total the rep reads out.
     */
    fun linesOf(productId: String): List<OrderLine> = order.lines
        .filter { it.productId == productId }
        .sortedByDescending { it.conversionRate }

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
    private val stockRepository: StockRepository,
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
                is DataResult.Success -> {
                    // What the stock count found short of par, converted into whole
                    // sale units. Failure here costs the suggestions and nothing
                    // else — the rep can still write the order by hand.
                    val suggestions = suggestionsFor(stop?.customer, result.data)

                    _state.update {
                        it.copy(
                            loading = false,
                            customerName = stop?.customer?.name.orEmpty(),
                            catalogue = result.data,
                            suggestions = suggestions,
                            suggestionsApplied = false,
                            order = DraftOrder(visitId = visitId, customerId = customerId),
                        )
                    }
                }

                is DataResult.Failure -> _state.update {
                    it.copy(loading = false, error = "Không tải được danh mục sản phẩm")
                }
            }
        }
    }

    /**
     * Reads the count for this visit and turns whatever is below par into whole
     * sale units. All three inputs are separately survivable: no must-stock list, no
     * count, or a catalogue miss each simply produce fewer suggestions.
     */
    private suspend fun suggestionsFor(
        customer: Customer?,
        catalogue: List<PricedProduct>,
    ): List<OrderSuggestion> {
        val mustStock = (
            catalogRepository.mustStock(
                channelId = customer?.channelId,
                shopTypeId = customer?.shopTypeId,
                on = LocalDate.now(),
            ) as? DataResult.Success
            )?.data ?: return emptyList()

        val counted = (stockRepository.countedBaseQty(visitId) as? DataResult.Success)
            ?.data ?: return emptyList()

        return orderSuggestions(
            mustStock = mustStock,
            countedBaseQty = counted,
            catalogue = catalogue,
        )
    }

    /**
     * Puts the suggested quantities into the order.
     *
     * Deliberately an action the rep takes rather than something that has already
     * happened when the screen opens. An order is a commitment to the customer, and
     * a screen that arrives pre-filled invites submitting quantities nobody agreed
     * to. Each line stays editable afterwards, and applying twice is harmless
     * because withLine replaces rather than accumulates.
     */
    fun applySuggestions() {
        val current = _state.value
        if (current.suggestions.isEmpty()) return

        var order = current.order
        var chosen = current.chosenUnit

        for (suggestion in current.suggestions) {
            val product = current.catalogue
                .firstOrNull { it.product.id == suggestion.productId } ?: continue

            // A suggestion can span units — one case plus two loose pieces — and each
            // part is its own line, which the order keys by product *and* unit.
            for (part in suggestion.parts) {
                val unit = product.units
                    .firstOrNull { it.unit.uomCode == part.uomCode } ?: continue
                order = order.withLine(lineFor(product, unit, part.qty))
            }

            // Move the picker onto the biggest part's unit, or the rep would see a
            // case quantity sitting under a "piece" selection. The smaller parts are
            // still listed on the row, so nothing applied here is hidden.
            suggestion.primaryPart?.let { chosen = chosen + (suggestion.productId to it.uomCode) }
        }

        _state.update {
            it.copy(
                order = order,
                chosenUnit = chosen,
                suggestionsApplied = true,
                error = null,
            )
        }
    }

    fun onQueryChange(value: String) = _state.update { it.copy(query = value) }

    /**
     * Switching unit changes which unit the stepper edits, and moves nothing.
     *
     * It used to carry the quantity across and delete the old line, to stop 5 cases
     * turning into 5 cases *and* 5 pieces when a rep changed their mind. That was the
     * right trade while the row could only show one line, but it destroys quantities
     * now that a suggestion can legitimately put a case and a few loose pieces on the
     * same product: switching the picker to pieces would have overwritten the two
     * suggested pieces with the one case's quantity, losing both.
     *
     * Every line on the product is now listed on the row, so the hazard it was
     * guarding against is visible instead of hidden, and a rep who did mean to change
     * unit can zero the line they no longer want.
     */
    fun onUnitChange(product: PricedProduct, uomCode: String) {
        if (product.units.none { it.unit.uomCode == uomCode }) return

        _state.update {
            it.copy(
                chosenUnit = it.chosenUnit + (product.product.id to uomCode),
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
                    it.copy(submitting = false, error = "Không lưu được đơn hàng")
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
