package com.tinhcd.myesalessfa.data.remote.service

import com.tinhcd.myesalessfa.data.remote.dto.WorkPlanBoardDto
import com.tinhcd.myesalessfa.data.remote.dto.PlannableCustomersDto
import com.tinhcd.myesalessfa.data.remote.dto.WorkPlanCommandPayload
import com.tinhcd.myesalessfa.data.remote.dto.WorkPlanDetailDto
import com.tinhcd.myesalessfa.data.remote.dto.WorkPlanWriteAckDto
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

interface WorkPlanService {

    /** List, counts, calendar and catalogue together. */
    @GET("work-plans")
    suspend fun board(
        @Query("fromDate") fromDate: String? = null,
        @Query("toDate") toDate: String? = null,
        @Query("statuses") statuses: String? = null,
        @Query("assignedToMe") assignedToMe: Boolean? = null,
    ): Response<WorkPlanBoardDto>

    @GET("work-plans")
    suspend fun detail(@Query("planId") planId: String): Response<WorkPlanDetailWrapper>

    /** The outlets this rep may plan a day with. */
    @GET("work-plans")
    suspend fun plannableCustomers(
        @Query("pickCustomers") query: String,
    ): Response<PlannableCustomersDto>

    /** Save, drive the status, or throw a draft away. */
    @POST("submit-work-plan")
    suspend fun command(@Body payload: WorkPlanCommandPayload): Response<WorkPlanWriteAckDto>
}

/** The detail endpoint wraps its one plan, the same as the write endpoints do. */
@kotlinx.serialization.Serializable
data class WorkPlanDetailWrapper(val plan: WorkPlanDetailDto)
