package com.tinhcd.myesalessfa.feature.incall.steps

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tinhcd.myesalessfa.domain.DataResult
import com.tinhcd.myesalessfa.domain.getOrNull
import com.tinhcd.myesalessfa.domain.model.DraftOrder
import com.tinhcd.myesalessfa.domain.model.OrderLine
import com.tinhcd.myesalessfa.domain.model.OrderSuggestion
import com.tinhcd.myesalessfa.domain.model.PricedProduct
import com.tinhcd.myesalessfa.domain.model.PricedUnit
import com.tinhcd.myesalessfa.domain.model.ProductSort
import com.tinhcd.myesalessfa.domain.model.browse
import com.tinhcd.myesalessfa.domain.model.categoryNames
import com.tinhcd.myesalessfa.domain.model.toCartLines
import com.tinhcd.myesalessfa.domain.repository.OrderRepository
import com.tinhcd.myesalessfa.domain.repository.SiteStockRepository
import com.tinhcd.myesalessfa.domain.usecase.GetOrderSuggestionsUseCase
import com.tinhcd.myesalessfa.domain.usecase.GetVisitCatalogueUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

/**
 * The three screens the legacy app splits ordering across, as pages of one step.
 *
 * They are pages rather than nav destinations because all three edit one draft
 * order and all three need the same visit. Three routes would mean three copies
 * of the arguments and a draft that has to live somewhere outside them.
 */
enum class TakeOrderPage {
    /** Giỏ hàng — what has been ordered so far. Where the step opens. */
    BASKET,

    /** Danh sách sản phẩm — the catalogue, reached by the basket's + button. */
    PRODUCTS,

    /** Xác nhận đơn hàng — the totals and the note, before anything is sent. */
    CONFIRM,
}

/** The line the edit sheet is open on. Null when the sheet is closed. */
data class EditingLine(
    val productId: String,
    /** The unit the line is currently recorded in — what an edit replaces. */
    val fromUomCode: String,
    val uomCode: String,
    val qty: Int,
)

data class TakeOrderUiState(
    val loading: Boolean = true,
    val page: TakeOrderPage = TakeOrderPage.BASKET,
    val customerName: String = "",
    val query: String = "",
    val sort: ProductSort = ProductSort.DEFAULT,
    /** Empty means every category, which is how the legacy filter starts. */
    val categories: Set<String> = emptySet(),
    val catalogue: List<PricedProduct> = emptyList(),
    /**
     * Warehouse stock in base units, product id -> quantity.
     *
     * Empty when the warehouse could not be read, and that is not an error: the
     * legacy row prints the figure beside the unit as information, and an order
     * is still writable without it.
     */
    val available: Map<String, Int> = emptyMap(),
    /** Which unit the rep has picked per product; absent means the default. */
    val chosenUnit: Map<String, String> = emptyMap(),
    /**
     * Quantities typed on the product list and not yet in the basket.
     *
     * The legacy list does not add on each keystroke: the rep walks the shelf,
     * types across many rows, and commits them with one "Cập nhật giỏ hàng".
     * Keyed by product, because the row edits whichever unit the product is
     * currently showing.
     */
    val draftQty: Map<String, Int> = emptyMap(),
    val order: DraftOrder = DraftOrder(visitId = "", customerId = "", id = ""),
    /**
     * Replenishment for must-stock SKUs the count found below par. Offered, never
     * applied on arrival — see [TakeOrderViewModel.applySuggestions].
     */
    val suggestions: List<OrderSuggestion> = emptyList(),
    val suggestionsApplied: Boolean = false,
    val editing: EditingLine? = null,
    /** The stored basket could not be read, so the screen started from nothing. */
    val cartUnavailable: Boolean = false,
    /**
     * The basket on screen is ahead of the stored one — the last push failed.
     *
     * Worth saying, quietly: the rep can keep working and send the order, which
     * goes through its own call. What they lose is the safety net, and finding
     * that out by coming back to an empty basket is the thing this whole table
     * exists to prevent.
     */
    val cartSyncFailed: Boolean = false,
    val submitting: Boolean = false,
    val error: String? = null,
    val finished: Boolean = false,
) {
    fun suggestionFor(productId: String): OrderSuggestion? =
        suggestions.firstOrNull { it.productId == productId }

    fun product(productId: String): PricedProduct? =
        catalogue.firstOrNull { it.product.id == productId }

    /**
     * The basket in a stable reading order: by product, then biggest unit first.
     *
     * Not the order the lines were added in. Editing a line rewrites it, which
     * would otherwise send that card to the bottom of the basket the moment the
     * rep corrected a quantity — the one card they were looking at.
     */
    val basketLines: List<OrderLine>
        get() = order.lines.sortedWith(
            compareBy<OrderLine> { it.productName }.thenByDescending { it.conversionRate },
        )

    /** The product list as the rep has narrowed it. */
    val visible: List<PricedProduct>
        get() = catalogue.browse(
            query = query,
            categories = categories,
            sort = sort,
            qtyOf = { order.baseQtyOf(it) },
        )

    val allCategories: List<String> get() = catalogue.categoryNames()

    fun unitFor(product: PricedProduct): PricedUnit {
        val code = chosenUnit[product.product.id] ?: return product.defaultUnit
        return product.units.firstOrNull { it.unit.uomCode == code } ?: product.defaultUnit
    }

    /**
     * What the product list's field shows: the pending edit if the rep has typed
     * one, otherwise what is already in the basket for that product's unit.
     */
    fun listQtyOf(product: PricedProduct): Int {
        draftQty[product.product.id]?.let { return it }
        return order.quantityOf(product.product.id, unitFor(product).unit.uomCode)
    }

    /** Warehouse stock for [product] expressed in the unit the row is showing. */
    fun availableIn(product: PricedProduct): Int? {
        val base = available[product.product.id] ?: return null
        val rate = unitFor(product).unit.conversionRate.coerceAtLeast(1)
        return base / rate
    }

    val hasDraft: Boolean get() = draftQty.isNotEmpty()

    /** Lines whose unit price is zero, which the ERP will not book. */
    val unpricedLines: List<OrderLine> get() = order.lines.filter { it.unitPrice <= 0 }
}

/**
 * Backs the `take_order` step.
 *
 * The order is totalled here so the rep can read a figure to the customer before
 * anything has been sent, but the figures are advisory: `submit_order` re-prices
 * the order from the same effective-dated catalogue and its answer is the one
 * that is booked. The device's total travels with the order so the two can be
 * compared after the fact.
 *
 * Promotions are not calculated. The legacy checkout carries automatic and manual
 * promotions, rewards and order-level discounts; none of that is here yet, and
 * the totals below are the plain arithmetic of the lines.
 */
@HiltViewModel
class TakeOrderViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val getVisitCatalogue: GetVisitCatalogueUseCase,
    private val getOrderSuggestions: GetOrderSuggestionsUseCase,
    private val siteStockRepository: SiteStockRepository,
    private val orderRepository: OrderRepository,
) : ViewModel() {

    private val visitId: String = checkNotNull(savedStateHandle["visitId"])
    private val customerId: String = checkNotNull(savedStateHandle["customerId"])

    private val _state = MutableStateFlow(TakeOrderUiState())
    val state: StateFlow<TakeOrderUiState> = _state.asStateFlow()

    /** The in-flight basket push, cancelled by the next one. */
    private var cartPush: Job? = null

    init {
        load()
    }

    fun load() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            // The customer's class decides the price list that applies, so it is
            // needed before anything can be shown with a figure next to it.
            when (val visit = getVisitCatalogue(customerId)) {
                is DataResult.Success -> {
                    // What the stock count found short of par, converted into whole
                    // sale units. Failure here costs the suggestions and nothing
                    // else — the rep can still write the order by hand.
                    val suggestions = getOrderSuggestions(visitId, visit.data)

                    // Decorative, as it is in the legacy row. Asked for after the
                    // catalogue rather than beside it so a warehouse that is slow
                    // or down cannot hold up the screen the rep came here for.
                    val available = siteStockRepository.load(null)
                        .getOrNull()
                        ?.items
                        ?.associate { it.productId to it.qtyBase }
                        .orEmpty()

                    // The basket the rep left here, priced against today's
                    // catalogue. A line whose product or unit has since gone from
                    // the catalogue is dropped rather than carried at a stale
                    // price — the server would refuse to book it anyway.
                    val stored = orderRepository.cart(customerId)
                    val restored = stored.getOrNull()
                        .orEmpty()
                        .mapNotNull { cartLine ->
                            val product = visit.data.catalogue
                                .firstOrNull { it.product.id == cartLine.productId }
                                ?: return@mapNotNull null
                            val unit = product.units
                                .firstOrNull { it.unit.uomCode == cartLine.uomCode }
                                ?: return@mapNotNull null
                            lineFor(product, unit, cartLine.qty)
                        }

                    _state.update {
                        it.copy(
                            loading = false,
                            customerName = visit.data.customer?.name.orEmpty(),
                            catalogue = visit.data.catalogue,
                            available = available,
                            suggestions = suggestions,
                            suggestionsApplied = false,
                            order = DraftOrder(
                                visitId = visitId,
                                customerId = customerId,
                                id = UUID.randomUUID().toString(),
                                lines = restored,
                            ),
                            // Not an error to act on: the rep can build the
                            // basket again. Said out loud because a basket that
                            // came back empty when they left one is otherwise
                            // indistinguishable from having sold nothing here.
                            cartUnavailable = stored is DataResult.Failure,
                        )
                    }
                }

                is DataResult.Failure -> _state.update {
                    it.copy(loading = false, error = "Không tải được danh mục sản phẩm")
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Moving between the three pages
    // -------------------------------------------------------------------------

    /**
     * Opens the catalogue. Anything typed on a previous visit to it is dropped:
     * the basket is the record of what was agreed, and a stale pending quantity
     * reappearing under a rep's finger is worse than making them type it again.
     */
    fun openProducts() = _state.update {
        it.copy(page = TakeOrderPage.PRODUCTS, draftQty = emptyMap(), error = null)
    }

    fun openBasket() = _state.update {
        it.copy(page = TakeOrderPage.BASKET, draftQty = emptyMap(), error = null)
    }

    /**
     * Moves to the confirmation, refusing an order carrying a line the ERP cannot
     * book. The legacy basket runs the same check on its confirm button, and it
     * belongs there rather than on submit: a rep told at the last step which
     * product is unpriced can still remove it and keep the rest of the order.
     */
    fun openConfirm() {
        val current = _state.value
        if (!current.order.canSubmit) return

        val unpriced = current.unpricedLines
        if (unpriced.isNotEmpty()) {
            _state.update {
                it.copy(
                    error = "Sản phẩm chưa có giá, bỏ khỏi giỏ hàng trước khi gửi: " +
                        unpriced.joinToString { line -> line.productName },
                )
            }
            return
        }

        _state.update { it.copy(page = TakeOrderPage.CONFIRM, error = null) }
    }

    // -------------------------------------------------------------------------
    // The product list
    // -------------------------------------------------------------------------

    fun onQueryChange(value: String) = _state.update { it.copy(query = value) }

    fun onSortChange(sort: ProductSort) = _state.update { it.copy(sort = sort) }

    fun onCategoryToggle(name: String) = _state.update {
        val next = if (name in it.categories) it.categories - name else it.categories + name
        it.copy(categories = next)
    }

    fun clearCategories() = _state.update { it.copy(categories = emptySet()) }

    fun onDraftQtyChange(product: PricedProduct, qty: Int) = _state.update {
        it.copy(
            draftQty = it.draftQty + (product.product.id to qty.coerceAtLeast(0)),
            error = null,
        )
    }

    /**
     * Writes everything typed on the list into the basket at once.
     *
     * A zero commits as a removal rather than being skipped. The legacy list
     * filters zeroes out and leaves removal to the basket's delete button, which
     * means typing 0 there looks like it worked and does nothing — the one
     * behaviour here that deliberately departs from it.
     */
    fun commitDraft() {
        val current = _state.value
        if (current.draftQty.isEmpty()) {
            _state.update { it.copy(error = "Nhập số lượng cho ít nhất một sản phẩm") }
            return
        }

        var order = current.order
        for ((productId, qty) in current.draftQty) {
            val product = current.product(productId) ?: continue
            val unit = current.unitFor(product)
            order = if (qty == 0) {
                order.withoutLine(productId, unit.unit.uomCode)
            } else {
                order.withLine(lineFor(product, unit, qty))
            }
        }

        _state.update {
            it.copy(
                order = order,
                draftQty = emptyMap(),
                page = TakeOrderPage.BASKET,
                error = null,
            )
        }

        pushCart()
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
            val product = current.product(suggestion.productId) ?: continue

            // A suggestion can span units — one case plus two loose pieces — and each
            // part is its own line, which the order keys by product *and* unit.
            for (part in suggestion.parts) {
                val unit = product.units
                    .firstOrNull { it.unit.uomCode == part.uomCode } ?: continue
                order = order.withLine(lineFor(product, unit, part.qty))
            }

            // Move the row onto the biggest part's unit, or the rep would see a case
            // quantity sitting under a "piece" label. The smaller parts are their own
            // basket cards, so nothing applied here is hidden.
            suggestion.primaryPart?.let { chosen = chosen + (suggestion.productId to it.uomCode) }
        }

        _state.update {
            it.copy(
                order = order,
                chosenUnit = chosen,
                // Applying replaces whatever was typed for those products; leaving
                // both would let one silently overwrite the other on commit.
                draftQty = it.draftQty - current.suggestions.map { s -> s.productId }.toSet(),
                suggestionsApplied = true,
                error = null,
            )
        }

        pushCart()
    }

    // -------------------------------------------------------------------------
    // The basket
    // -------------------------------------------------------------------------

    fun startEdit(line: OrderLine) = _state.update {
        it.copy(
            editing = EditingLine(
                productId = line.productId,
                fromUomCode = line.uomCode,
                uomCode = line.uomCode,
                qty = line.qty,
            ),
        )
    }

    fun onEditUnitChange(uomCode: String) = _state.update { state ->
        val editing = state.editing ?: return@update state
        val product = state.product(editing.productId) ?: return@update state
        if (product.units.none { it.unit.uomCode == uomCode }) return@update state
        state.copy(editing = editing.copy(uomCode = uomCode))
    }

    fun onEditQtyChange(qty: Int) = _state.update { state ->
        val editing = state.editing ?: return@update state
        state.copy(editing = editing.copy(qty = qty.coerceAtLeast(0)))
    }

    fun cancelEdit() = _state.update { it.copy(editing = null) }

    /**
     * Commits the sheet. A changed unit moves the quantity rather than adding a
     * second line: the rep opened one line and is editing that line, and leaving
     * the old unit behind would double the order without a second thought.
     */
    fun applyEdit() {
        val current = _state.value
        val editing = current.editing ?: return
        val product = current.product(editing.productId) ?: return
        val unit = product.units.firstOrNull { it.unit.uomCode == editing.uomCode } ?: return

        if (editing.qty <= 0) {
            _state.update { it.copy(error = "Nhập số lượng lớn hơn 0") }
            return
        }

        val order = current.order
            .withoutLine(editing.productId, editing.fromUomCode)
            .withLine(lineFor(product, unit, editing.qty))

        _state.update {
            it.copy(
                order = order,
                chosenUnit = it.chosenUnit + (editing.productId to editing.uomCode),
                editing = null,
                error = null,
            )
        }

        pushCart()
    }

    fun removeLine(line: OrderLine) {
        _state.update {
            it.copy(
                order = it.order.withoutLine(line.productId, line.uomCode),
                editing = null,
                error = null,
            )
        }

        pushCart()
    }

    // -------------------------------------------------------------------------
    // Confirmation
    // -------------------------------------------------------------------------

    fun onNoteChange(value: String) = _state.update {
        it.copy(order = it.order.copy(note = value))
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

    /**
     * Stores the basket as it now stands.
     *
     * Fire and forget, and the screen never waits on it: the rep is typing
     * quantities at a counter, and a spinner between keystrokes would make the
     * step unusable to protect against a failure they can see reported instead.
     *
     * The previous push is cancelled rather than queued. Every push carries the
     * whole basket, so an older one landing after a newer one would put the
     * stored basket a step behind the screen — last write must be the last edit.
     */
    private fun pushCart() {
        val order = _state.value.order
        cartPush?.cancel()
        cartPush = viewModelScope.launch {
            val saved = orderRepository.saveCart(customerId, order.toCartLines())
            _state.update { it.copy(cartSyncFailed = saved is DataResult.Failure) }
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
