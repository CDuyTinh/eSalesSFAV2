package com.tinhcd.myesalessfa.data.remote.service

import com.tinhcd.myesalessfa.data.remote.dto.FeedbackPayload
import com.tinhcd.myesalessfa.data.remote.dto.WriteAckDto
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST

interface FeedbackService {

    /**
     * Forwards to `submit_feedback`. Any recording must already be in storage when
     * this is called, for the same reason the display audit's photos must be.
     */
    @POST("submit-feedback")
    suspend fun submitFeedback(@Body feedback: FeedbackPayload): Response<WriteAckDto>
}
