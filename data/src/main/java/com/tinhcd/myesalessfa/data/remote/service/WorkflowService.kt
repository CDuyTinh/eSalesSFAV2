package com.tinhcd.myesalessfa.data.remote.service

import com.tinhcd.myesalessfa.data.remote.dto.VisitWorkflowDto
import com.tinhcd.myesalessfa.data.remote.dto.WriteAckDto
import kotlinx.serialization.json.JsonObject
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

/** Which in-call steps this visit has finished, and marking one finished. */
interface WorkflowService {

    /** Step completions for a visit, assembled against the step list in :domain. */
    @GET("visit-workflow")
    suspend fun visitWorkflow(@Query("visitId") visitId: String): VisitWorkflowDto

    @POST("submit-step")
    suspend fun submitStep(@Body row: JsonObject): Response<WriteAckDto>
}
