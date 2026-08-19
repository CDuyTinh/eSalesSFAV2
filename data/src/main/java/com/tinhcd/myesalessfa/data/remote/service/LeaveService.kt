package com.tinhcd.myesalessfa.data.remote.service

import com.tinhcd.myesalessfa.data.remote.dto.LeaveBoardDto
import com.tinhcd.myesalessfa.data.remote.dto.NewLeaveRequestDto
import com.tinhcd.myesalessfa.data.remote.dto.WithdrawLeaveDto
import com.tinhcd.myesalessfa.data.remote.dto.WriteAckDto
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST

/**
 * [submit] and [withdraw] return [Response] because their refusals are the
 * useful part: an overlapping period, or a request somebody has already ruled on.
 */
interface LeaveService {

    @GET("leave-requests")
    suspend fun board(): Response<LeaveBoardDto>

    @POST("leave-requests")
    suspend fun submit(@Body request: NewLeaveRequestDto): Response<WriteAckDto>

    @PATCH("leave-requests")
    suspend fun withdraw(@Body request: WithdrawLeaveDto): Response<WriteAckDto>
}
