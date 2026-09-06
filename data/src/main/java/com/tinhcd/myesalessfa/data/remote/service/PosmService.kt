package com.tinhcd.myesalessfa.data.remote.service

import com.tinhcd.myesalessfa.data.remote.dto.PosmCheckPayload
import com.tinhcd.myesalessfa.data.remote.dto.PosmDto
import com.tinhcd.myesalessfa.data.remote.dto.WriteAckDto
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

interface PosmService {

    /**
     * What is at the outlet and what it is waiting for. Keyed by the visit rather
     * than the date, so an afternoon call starts with the assets unchecked.
     */
    @GET("posm")
    suspend fun load(
        @Query("customerId") customerId: String,
        @Query("visitId") visitId: String,
    ): Response<PosmDto>

    /**
     * Forwards to `submit_posm_check`, which checks the step's own photo_min,
     * that every storage path exists, and that the asset is really at this
     * outlet. The photos must already be uploaded when this is called.
     */
    @POST("submit-posm-check")
    suspend fun submitCheck(@Body check: PosmCheckPayload): Response<WriteAckDto>
}
