package com.tinhcd.myesalessfa.data.remote.service

import com.tinhcd.myesalessfa.data.remote.dto.SiteStockDto
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Query

interface SiteStockService {

    /** Omitting the site asks for the branch's first one. */
    @GET("site-stock")
    suspend fun load(@Query("site_id") siteId: String?): Response<SiteStockDto>
}
