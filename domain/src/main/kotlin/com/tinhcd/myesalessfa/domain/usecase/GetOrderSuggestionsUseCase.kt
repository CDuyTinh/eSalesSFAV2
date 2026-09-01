package com.tinhcd.myesalessfa.domain.usecase

import com.tinhcd.myesalessfa.domain.getOrNull
import com.tinhcd.myesalessfa.domain.model.OrderSuggestion
import com.tinhcd.myesalessfa.domain.model.orderSuggestions
import com.tinhcd.myesalessfa.domain.repository.StockRepository
import javax.inject.Inject

/**
 * What this visit's stock count says the outlet should reorder.
 *
 * Takes the visit's [VisitCatalogue] rather than fetching its own: the order screen
 * has already loaded it to show prices, and re-reading the must-stock lists here
 * would risk suggesting against par levels the screen is not displaying.
 *
 * Every input is separately survivable — no must-stock list, no count, or a product
 * missing from the catalogue each produce fewer suggestions and no error. The
 * suggestions are an offer; an empty offer is a normal outcome, and the rep writes
 * the order by hand regardless.
 */
class GetOrderSuggestionsUseCase @Inject constructor(
    private val stockRepository: StockRepository,
) {
    suspend operator fun invoke(
        visitId: String,
        visit: VisitCatalogue,
    ): List<OrderSuggestion> {
        if (visit.mustStock.isEmpty()) return emptyList()

        val counted = stockRepository.countedBaseQty(visitId).getOrNull() ?: return emptyList()

        return orderSuggestions(
            mustStock = visit.mustStock,
            countedBaseQty = counted,
            catalogue = visit.catalogue,
        )
    }
}
