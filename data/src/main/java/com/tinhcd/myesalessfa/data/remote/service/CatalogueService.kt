package com.tinhcd.myesalessfa.data.remote.service

import com.tinhcd.myesalessfa.data.remote.dto.CatalogueDto
import retrofit2.http.GET

interface CatalogueService {

    /**
     * Products with their sale units nested, plus the price rules that apply to
     * this rep. The function pages internally, so this is the whole catalogue and
     * not the first page of it.
     */
    @GET("catalogue")
    suspend fun catalogue(): CatalogueDto
}
