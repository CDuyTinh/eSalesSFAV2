package com.tinhcd.myesalessfa.domain.model

import java.time.LocalDate

/**
 * One running promotion as Chương trình khuyến mãi lists it (the legacy's
 * TradePromotion): what it counts, its levels, what it gives, and the budget
 * the rep's route has left of it.
 */
data class CataloguePromotion(
    val sequenceId: String,
    val programCode: String,
    val programName: String,
    val name: String,
    val scope: PromotionScope,
    /** "qty" or "amount": what a level's threshold is counted in. */
    val breakBy: String,
    val reward: PromotionReward,
    val fromDate: LocalDate?,
    /** Null for a promotion with no end date. */
    val toDate: LocalDate?,
    val isBundle: Boolean,
    /** The "CTKM lựa chọn" group it belongs to, if any. */
    val proposalName: String?,
    /** Limited to some outlets, channels or classes. */
    val targeted: Boolean,
    val items: List<CatalogueItem>,
    val levels: List<CatalogueLevel>,
    val gifts: List<CatalogueGift>,
    val budget: CatalogueBudget?,
) {
    val byQty: Boolean get() = breakBy == "qty"
}

data class CatalogueItem(val productCode: String, val productName: String, val uomName: String)

data class CatalogueLevel(
    val name: String,
    val breakQty: Double,
    val breakAmt: Double,
    /** Money off per lot, or the percentage for a percentage rule. */
    val discAmt: Double,
    val maxLot: Int,
    val customerMaxLot: Int?,
)

data class CatalogueGift(val productName: String, val uomName: String, val qty: Int)

data class CatalogueBudget(
    val name: String,
    /** "amount", "lots" or "free_item": what the figures below count. */
    val counts: String,
    val allocated: Double?,
    val spent: Double?,
    val remaining: Double?,
)
