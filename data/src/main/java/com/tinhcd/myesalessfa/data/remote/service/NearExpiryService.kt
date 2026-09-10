package com.tinhcd.myesalessfa.data.remote.service

import com.tinhcd.myesalessfa.data.remote.dto.NearExpiryDto
import com.tinhcd.myesalessfa.data.remote.dto.NearExpiryPayload
import com.tinhcd.myesalessfa.data.remote.dto.WriteAckDto
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

interface NearExpiryService {

    /** The lots this visit already wrote down, so reopening shows them. */
    @GET("near-expiry")
    suspend fun load(@Query("visitId") visitId: String): Response<NearExpiryDto>

    /**
     * Files the document. The photos must already be uploaded when this is
     * called, and the server refuses a check with neither photo nor reason.
     */
    @POST("submit-near-expiry")
    suspend fun submit(@Body payload: NearExpiryPayload): Response<WriteAckDto>
}
