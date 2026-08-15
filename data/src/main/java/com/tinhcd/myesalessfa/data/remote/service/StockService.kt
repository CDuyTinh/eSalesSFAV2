package com.tinhcd.myesalessfa.data.remote.service

import com.tinhcd.myesalessfa.data.remote.dto.PreviousCountDto
import com.tinhcd.myesalessfa.data.remote.dto.StockCountPayload
import com.tinhcd.myesalessfa.data.remote.dto.VisitCountDto
import com.tinhcd.myesalessfa.data.remote.dto.WriteAckDto
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

interface StockService {

    /**
     * The outlet's last stock count, totalled per product in base units.
     * [exceptVisitId] leaves out the visit in progress, so a recount compares
     * against the previous visit rather than the attempt it replaces.
     */
    @GET("previous-count")
    suspend fun previousCount(
        @Query("customerId") customerId: String,
        @Query("exceptVisitId") exceptVisitId: String,
    ): PreviousCountDto

    /**
     * This visit's own count, which is what the order screen measures against par.
     * The inverse of [previousCount], which excludes it.
     */
    @GET("visit-count")
    suspend fun visitCount(@Query("visitId") visitId: String): VisitCountDto

    @POST("submit-stock-count")
    suspend fun submitStockCount(@Body count: StockCountPayload): Response<WriteAckDto>
}
