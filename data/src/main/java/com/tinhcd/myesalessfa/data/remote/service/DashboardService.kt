package com.tinhcd.myesalessfa.data.remote.service

import com.tinhcd.myesalessfa.data.remote.dto.DashboardDto
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Query

interface DashboardService {

    /**
     * Every figure on the Overview tab, from one aggregate read.
     *
     * The date is sent rather than left to the server, so a rep still working
     * after midnight sees the day they are standing in rather than the day the
     * database happens to be having.
     *
     * [Response] rather than a bare body, for the reason `/bootstrap` learned the
     * hard way: Retrofit throws away an error body, and a failure here would
     * otherwise reach the screen as a blank dashboard with nothing to explain it.
     */
    @GET("dashboard")
    suspend fun dashboard(@Query("date") date: String): Response<DashboardDto>
}
