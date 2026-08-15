package com.tinhcd.myesalessfa.data.remote.service

import com.tinhcd.myesalessfa.data.remote.dto.NewVisitDto
import com.tinhcd.myesalessfa.data.remote.dto.WriteAckDto
import kotlinx.serialization.json.JsonObject
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST

/**
 * The visit's own lifecycle: arriving at the outlet and leaving it.
 *
 * These return [Response] rather than the body so the error survives: a guard inside
 * one of the database functions reports through `message`, and that is what should
 * reach the rep instead of "HTTP 400".
 */
interface VisitService {

    /** Check-in. The rep's identity is derived server-side, not sent. */
    @POST("submit-visit")
    suspend fun submitVisit(@Body visit: NewVisitDto): Response<WriteAckDto>

    /** Two fields and no shared type worth declaring for them. */
    @POST("submit-checkout")
    suspend fun submitCheckout(@Body row: JsonObject): Response<WriteAckDto>
}
