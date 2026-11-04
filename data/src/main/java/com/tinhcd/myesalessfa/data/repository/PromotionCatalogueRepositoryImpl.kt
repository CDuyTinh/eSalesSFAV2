package com.tinhcd.myesalessfa.data.repository

import com.tinhcd.myesalessfa.data.remote.dto.CataloguePromotionDto
import com.tinhcd.myesalessfa.data.remote.http.orThrow
import com.tinhcd.myesalessfa.data.remote.service.PromotionCatalogueService
import com.tinhcd.myesalessfa.domain.DataResult
import com.tinhcd.myesalessfa.domain.model.CatalogueBudget
import com.tinhcd.myesalessfa.domain.model.CatalogueGift
import com.tinhcd.myesalessfa.domain.model.CatalogueItem
import com.tinhcd.myesalessfa.domain.model.CatalogueLevel
import com.tinhcd.myesalessfa.domain.model.CataloguePromotion
import com.tinhcd.myesalessfa.domain.model.PromotionReward
import com.tinhcd.myesalessfa.domain.model.PromotionScope
import com.tinhcd.myesalessfa.domain.repository.PromotionCatalogueRepository
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PromotionCatalogueRepositoryImpl @Inject constructor(
    private val service: PromotionCatalogueService,
) : PromotionCatalogueRepository {

    override suspend fun running(): DataResult<List<CataloguePromotion>> = try {
        DataResult.Success(service.running().orThrow().map { it.toDomain() })
    } catch (e: Exception) {
        DataResult.Failure(e.toAppError())
    }
}

private fun CataloguePromotionDto.toDomain() = CataloguePromotion(
    sequenceId = sequenceId,
    programCode = programCode,
    programName = programName,
    name = name,
    scope = PromotionScope.fromWire(scope),
    breakBy = breakBy,
    reward = PromotionReward.fromWire(reward),
    fromDate = fromDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
    toDate = toDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
    isBundle = isBundle,
    proposalName = proposalName,
    targeted = targeted,
    items = items.map { CatalogueItem(it.productCode, it.productName, it.uomName) },
    levels = levels.map { CatalogueLevel(it.name, it.breakQty, it.breakAmt, it.discAmt, it.maxLot, it.customerMaxLot) },
    gifts = gifts.map { CatalogueGift(it.productName, it.uomName, it.qty) },
    budget = budget?.let { CatalogueBudget(it.name, it.counts, it.allocated, it.spent, it.remaining) },
)
