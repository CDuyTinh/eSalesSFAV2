package com.tinhcd.myesalessfa.domain.repository

import com.tinhcd.myesalessfa.domain.DataResult
import com.tinhcd.myesalessfa.domain.model.CataloguePromotion

/** Chương trình khuyến mãi: the promotions running for the rep's branch. */
interface PromotionCatalogueRepository {
    suspend fun running(): DataResult<List<CataloguePromotion>>
}
