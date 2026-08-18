package com.tinhcd.myesalessfa.data.remote.service

import com.tinhcd.myesalessfa.data.remote.dto.FocusProductsDto
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Query

interface FocusProductService {

    @GET("focus-products")
    suspend fun onDate(@Query("date") date: String): Response<FocusProductsDto>
}
