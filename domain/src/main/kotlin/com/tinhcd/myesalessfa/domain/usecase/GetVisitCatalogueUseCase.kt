package com.tinhcd.myesalessfa.domain.usecase

import com.tinhcd.myesalessfa.domain.DataResult
import com.tinhcd.myesalessfa.domain.getOrNull
import com.tinhcd.myesalessfa.domain.model.Customer
import com.tinhcd.myesalessfa.domain.model.PricedProduct
import com.tinhcd.myesalessfa.domain.repository.CatalogRepository
import com.tinhcd.myesalessfa.domain.repository.RouteRepository
import java.time.LocalDate
import javax.inject.Inject

/**
 * What one outlet may be sold today, and what it is obliged to hold.
 *
 * @param customer null when the stop could not be read. The catalogue is then the
 *  list-price one, which is worth showing: a rep with no signal for the route call
 *  can still count and still write an order.
 */
data class VisitCatalogue(
    val customer: Customer?,
    val catalogue: List<PricedProduct>,
    /** Product id -> par level in base units. Empty when no list covers the outlet. */
    val mustStock: Map<String, Int>,
)

/**
 * Assembles the catalogue the in-call steps work from.
 *
 * Three calls in a fixed order, because the second and third are scoped by what the
 * first returns: the customer's class picks the price list, and their channel and
 * shop type pick the must-stock lists. The stock count and the order both need
 * exactly this, and had a copy each — which is two places for the scoping to drift,
 * and would have let a rep count against one segment's par levels and then order
 * against another's.
 *
 * Only the catalogue is load-bearing. A missing stop costs the customer's name and
 * falls back to list prices; a missing must-stock list costs the par-level markers.
 * Neither stops the rep working, so neither is reported as a failure.
 */
class GetVisitCatalogueUseCase @Inject constructor(
    private val routeRepository: RouteRepository,
    private val catalogRepository: CatalogRepository,
) {
    suspend operator fun invoke(
        customerId: String,
        on: LocalDate = LocalDate.now(),
    ): DataResult<VisitCatalogue> {
        val customer = routeRepository.getStop(customerId, on).getOrNull()?.customer

        val catalogue = when (val result = catalogRepository.catalogue(customer?.classId, on)) {
            is DataResult.Success -> result.data
            is DataResult.Failure -> return result
        }

        val mustStock = catalogRepository.mustStock(
            channelId = customer?.channelId,
            shopTypeId = customer?.shopTypeId,
            on = on,
        ).getOrNull().orEmpty()

        return DataResult.Success(
            VisitCatalogue(customer = customer, catalogue = catalogue, mustStock = mustStock),
        )
    }
}
