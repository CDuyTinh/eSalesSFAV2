package com.tinhcd.myesalessfa.data.remote.service

import com.tinhcd.myesalessfa.data.remote.dto.ActivityReportDto
import com.tinhcd.myesalessfa.data.remote.dto.OrderReportDto
import com.tinhcd.myesalessfa.data.remote.dto.SalesReportDto
import com.tinhcd.myesalessfa.data.remote.dto.SkuReportDto
import com.tinhcd.myesalessfa.data.remote.dto.TradeReportDto
import com.tinhcd.myesalessfa.data.remote.dto.WorkResultReportDto
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Query

/** One endpoint, two reports: the query parameter is the question. */
interface ReportService {

    @GET("reports")
    suspend fun activities(@Query("activities") date: String): Response<ActivityReportDto>

    @GET("reports")
    suspend fun sales(@Query("sales") month: String): Response<SalesReportDto>

    // The other four arrive under `kind`, which is the question, with the
    // window and whatever that report narrows by.

    @GET("reports")
    suspend fun orders(
        @Query("fromDate") fromDate: String,
        @Query("toDate") toDate: String,
        @Query("statuses") statuses: String? = null,
        @Query("inRoute") inRoute: Boolean? = null,
        @Query("kind") kind: String = "orders",
    ): Response<OrderReportDto>

    @GET("reports")
    suspend fun sku(
        @Query("fromDate") fromDate: String,
        @Query("toDate") toDate: String,
        @Query("query") query: String? = null,
        @Query("kind") kind: String = "sku",
    ): Response<SkuReportDto>

    @GET("reports")
    suspend fun trade(
        @Query("fromDate") fromDate: String,
        @Query("toDate") toDate: String,
        @Query("kind") kind: String = "trade",
    ): Response<TradeReportDto>

    @GET("reports")
    suspend fun workResult(
        @Query("fromDate") fromDate: String,
        @Query("toDate") toDate: String,
        @Query("kind") kind: String = "work_result",
    ): Response<WorkResultReportDto>
}
