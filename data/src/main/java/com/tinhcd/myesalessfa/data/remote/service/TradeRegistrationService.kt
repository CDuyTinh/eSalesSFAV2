package com.tinhcd.myesalessfa.data.remote.service

import com.tinhcd.myesalessfa.data.remote.dto.RegisterableCustomersDto
import com.tinhcd.myesalessfa.data.remote.dto.TradeOffersDto
import com.tinhcd.myesalessfa.data.remote.dto.TradeRegistrationAckDto
import com.tinhcd.myesalessfa.data.remote.dto.TradeRegistrationBoardDto
import com.tinhcd.myesalessfa.data.remote.dto.TradeRegistrationPayload
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

/** One route, three questions and a write. */
interface TradeRegistrationService {

    @GET("trade-registrations")
    suspend fun board(
        @Query("kinds") kinds: String? = null,
        @Query("programIds") programIds: String? = null,
        @Query("statuses") statuses: String? = null,
        @Query("query") query: String? = null,
    ): Response<TradeRegistrationBoardDto>

    @GET("trade-registrations")
    suspend fun customers(
        @Query("pickCustomers") query: String,
    ): Response<RegisterableCustomersDto>

    @GET("trade-registrations")
    suspend fun offers(
        @Query("customerId") customerId: String,
    ): Response<TradeOffersDto>

    @POST("trade-registrations")
    suspend fun register(
        @Body payload: TradeRegistrationPayload,
    ): Response<TradeRegistrationAckDto>
}
