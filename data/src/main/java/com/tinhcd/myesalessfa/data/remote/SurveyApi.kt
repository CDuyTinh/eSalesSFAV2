package com.tinhcd.myesalessfa.data.remote

import com.tinhcd.myesalessfa.data.outbox.SurveyPayload
import com.tinhcd.myesalessfa.data.remote.http.orThrow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Survey submission.
 *
 * One endpoint for every questionnaire step: the payload's form id selects which
 * questionnaire it is, so posm_status, market_info and anything added later all go
 * through here. `submit_survey` recomputes the score from the question definitions —
 * no score travels from the device — and marks the step done in the same transaction.
 */
@Singleton
class SurveyApi @Inject constructor(
    private val service: FunctionsService,
) {
    suspend fun submit(payload: SurveyPayload) {
        service.submitSurvey(payload).orThrow()
    }
}
