package com.tinhcd.myesalessfa.data.remote.service

import com.tinhcd.myesalessfa.data.remote.dto.LoyaltyDto
import com.tinhcd.myesalessfa.data.remote.dto.LoyaltyRegistrationPayload
import com.tinhcd.myesalessfa.data.remote.dto.WriteAckDto
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

interface LoyaltyService {

    /** What the outlet is accumulating towards, and what it could still join. */
    @GET("loyalty")
    suspend fun load(@Query("customerId") customerId: String): Response<LoyaltyDto>

    /**
     * Signs the outlet up at a band. The server writes the status and spends the
     * slot; nothing in the payload can.
     */
    @POST("submit-loyalty-registration")
    suspend fun register(@Body payload: LoyaltyRegistrationPayload): Response<WriteAckDto>
}
