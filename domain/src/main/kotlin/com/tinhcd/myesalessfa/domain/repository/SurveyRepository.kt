package com.tinhcd.myesalessfa.domain.repository

import com.tinhcd.myesalessfa.domain.DataResult
import com.tinhcd.myesalessfa.domain.model.DraftSurvey
import com.tinhcd.myesalessfa.domain.model.SurveyDefinition

interface SurveyRepository {
    /**
     * The questionnaire configured for [formId], from the local cache so a
     * questionnaire step works with no signal. Null when the server has no active
     * questionnaire for that step.
     */
    suspend fun definition(formId: String): DataResult<SurveyDefinition?>

    /**
     * Sends [survey]. The server recomputes the score from the question definitions
     * and marks the step done in the same transaction.
     */
    suspend fun submit(survey: DraftSurvey): DataResult<Unit>
}
