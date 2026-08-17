package com.tinhcd.myesalessfa.data.remote.service

import com.tinhcd.myesalessfa.data.remote.dto.WorkDayDto
import com.tinhcd.myesalessfa.data.remote.dto.WorkDayPunchDto
import com.tinhcd.myesalessfa.data.remote.dto.WriteAckDto
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

/**
 * The selling day's own boundaries: clocking in at the depot and clocking out.
 *
 * [submitPunch] returns [Response] rather than the body because its refusals are
 * the useful part — the day is already open, or a visit is still running — and
 * those arrive as `message` on a 409 that would otherwise reach the rep as
 * "HTTP 409".
 */
interface TimekeepingService {

    @GET("work-day")
    suspend fun workDay(@Query("date") date: String): Response<WorkDayDto>

    @POST("submit-work-day")
    suspend fun submitPunch(@Body punch: WorkDayPunchDto): Response<WriteAckDto>
}
