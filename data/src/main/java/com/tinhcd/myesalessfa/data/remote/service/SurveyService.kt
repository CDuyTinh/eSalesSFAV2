package com.tinhcd.myesalessfa.data.remote.service

import com.tinhcd.myesalessfa.data.remote.dto.SurveyPayload
import com.tinhcd.myesalessfa.data.remote.dto.WriteAckDto
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST

interface SurveyService {

    /**
     * Forwards to `submit_survey`. One endpoint for every questionnaire step: the
     * payload's form id selects which questionnaire it is, and the server recomputes
     * the score from the question definitions.
     */
    @POST("submit-survey")
    suspend fun submitSurvey(@Body survey: SurveyPayload): Response<WriteAckDto>
}
