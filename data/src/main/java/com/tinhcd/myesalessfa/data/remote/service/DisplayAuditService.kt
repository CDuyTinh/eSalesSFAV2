package com.tinhcd.myesalessfa.data.remote.service

import com.tinhcd.myesalessfa.data.remote.dto.DisplayAuditPayload
import com.tinhcd.myesalessfa.data.remote.dto.WriteAckDto
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST

interface DisplayAuditService {

    /**
     * Forwards to `submit_display_audit`, which checks the step's own photo_min and
     * that every storage path it is handed actually exists. The photos must already
     * be uploaded when this is called.
     */
    @POST("submit-display-audit")
    suspend fun submitDisplayAudit(@Body audit: DisplayAuditPayload): Response<WriteAckDto>
}
