package com.tinhcd.myesalessfa.data.remote.service

import com.tinhcd.myesalessfa.data.remote.dto.PosmCheckPayload
import com.tinhcd.myesalessfa.data.remote.dto.PosmDto
import com.tinhcd.myesalessfa.data.remote.dto.PosmMovementPayload
import com.tinhcd.myesalessfa.data.remote.dto.PosmRegistrationPayload
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

    /**
     * Puts in a request for POSM. The server decides the status and the approved
     * quantity; nothing in the payload can.
     */
    @POST("submit-posm-registration")
    suspend fun register(@Body payload: PosmRegistrationPayload): Response<WriteAckDto>

    /**
     * Hands assets over or takes them back. The photos must already be uploaded
     * when this is called, and the server refuses a handover without one.
     */
    @POST("submit-posm-movement")
    suspend fun move(@Body payload: PosmMovementPayload): Response<WriteAckDto>
}
