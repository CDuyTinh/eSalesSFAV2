package com.tinhcd.myesalessfa.data.remote.service

import com.tinhcd.myesalessfa.data.remote.dto.DailyTargetsDto
import com.tinhcd.myesalessfa.data.remote.dto.SaveDailyTargetsDto
import com.tinhcd.myesalessfa.data.remote.dto.WriteAckDto
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

interface DailyTargetService {

    @GET("daily-targets")
    suspend fun stops(@Query("date") date: String): Response<DailyTargetsDto>

    @POST("daily-targets")
    suspend fun save(@Body plan: SaveDailyTargetsDto): Response<WriteAckDto>
}
