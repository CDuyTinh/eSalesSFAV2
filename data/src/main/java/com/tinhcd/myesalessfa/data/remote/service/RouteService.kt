package com.tinhcd.myesalessfa.data.remote.service

import com.tinhcd.myesalessfa.data.remote.dto.RouteDto
import retrofit2.http.GET
import retrofit2.http.Query

interface RouteService {

    /**
     * Stops with each visit's status already attached.
     *
     * One call replaced two round trips and a client-side join. The function derives
     * the weekday from the date next to the data, so the stops and the visits can no
     * longer disagree about which day they describe.
     */
    @GET("route")
    suspend fun route(@Query("date") date: String): RouteDto
}
