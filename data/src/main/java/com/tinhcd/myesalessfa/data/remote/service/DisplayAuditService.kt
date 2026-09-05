package com.tinhcd.myesalessfa.data.remote.service

import com.tinhcd.myesalessfa.data.remote.dto.DisplayAuditPayload
import com.tinhcd.myesalessfa.data.remote.dto.DisplayProgramsDto
import com.tinhcd.myesalessfa.data.remote.dto.WriteAckDto
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

interface DisplayAuditService {

    /**
     * What this outlet is audited on today. Keyed by the visit rather than the
     * date, so an afternoon call on a shop starts with its programmes unscored
     * instead of inheriting the morning's answers.
     */
    @GET("display-programs")
    suspend fun programs(
        @Query("customerId") customerId: String,
        @Query("visitId") visitId: String,
    ): Response<DisplayProgramsDto>

    /**
     * Forwards to `submit_display_audit`, which checks the step's own photo_min and
     * that every storage path it is handed actually exists. The photos must already
     * be uploaded when this is called.
     */
    @POST("submit-display-audit")
    suspend fun submitDisplayAudit(@Body audit: DisplayAuditPayload): Response<WriteAckDto>
}
