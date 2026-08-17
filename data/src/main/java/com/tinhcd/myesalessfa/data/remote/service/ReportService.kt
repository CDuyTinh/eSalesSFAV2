package com.tinhcd.myesalessfa.data.remote.service

import com.tinhcd.myesalessfa.data.remote.dto.ActivityReportDto
import com.tinhcd.myesalessfa.data.remote.dto.SalesReportDto
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Query

/** One endpoint, two reports: the query parameter is the question. */
interface ReportService {

    @GET("reports")
    suspend fun activities(@Query("activities") date: String): Response<ActivityReportDto>

    @GET("reports")
    suspend fun sales(@Query("sales") month: String): Response<SalesReportDto>
}
