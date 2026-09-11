package com.tinhcd.myesalessfa.data.remote.service

import com.tinhcd.myesalessfa.data.remote.dto.NotificationFeedDto
import com.tinhcd.myesalessfa.data.remote.dto.NotificationReadAckDto
import com.tinhcd.myesalessfa.data.remote.dto.NotificationReadPayload
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

interface NotificationService {

    /** The list and the badge in one call, so the two cannot disagree. */
    @GET("notifications")
    suspend fun feed(
        @Query("fromDate") fromDate: String? = null,
        @Query("toDate") toDate: String? = null,
        @Query("unreadOnly") unreadOnly: Boolean? = null,
    ): Response<NotificationFeedDto>

    @POST("read-notification")
    suspend fun markRead(@Body payload: NotificationReadPayload): Response<NotificationReadAckDto>
}
