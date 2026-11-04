package com.tinhcd.myesalessfa.data.remote.service

import com.tinhcd.myesalessfa.data.remote.dto.CataloguePromotionDto
import retrofit2.Response
import retrofit2.http.GET

/** `promotion-catalogue`: Chương trình khuyến mãi. */
interface PromotionCatalogueService {

    @GET("promotion-catalogue")
    suspend fun running(): Response<List<CataloguePromotionDto>>
}
