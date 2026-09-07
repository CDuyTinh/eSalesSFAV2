package com.tinhcd.myesalessfa.data.remote.service

import com.tinhcd.myesalessfa.data.remote.dto.CompetitorSurveyDto
import com.tinhcd.myesalessfa.data.remote.dto.CompetitorSurveyPayload
import com.tinhcd.myesalessfa.data.remote.dto.MarketInfoDto
import com.tinhcd.myesalessfa.data.remote.dto.WriteAckDto
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

interface MarketInfoService {

    /**
     * Every survey the outlet owes today, of both kinds. Keyed by the visit rather
     * than the date, so an afternoon call starts with them unanswered.
     */
    @GET("market-info")
    suspend fun surveys(
        @Query("customerId") customerId: String,
        @Query("visitId") visitId: String,
    ): Response<MarketInfoDto>

    /** One competitor survey, with whatever this visit has already answered. */
    @GET("market-info")
    suspend fun competitorSurvey(
        @Query("visitId") visitId: String,
        @Query("surveyId") surveyId: String,
    ): Response<CompetitorSurveyDto>

    /**
     * The whole grid at once. The photos must already be uploaded when this is
     * called, as with the POSM and display steps.
     */
    @POST("submit-competitor-survey")
    suspend fun submit(@Body payload: CompetitorSurveyPayload): Response<WriteAckDto>
}
