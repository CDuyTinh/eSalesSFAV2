package com.tinhcd.myesalessfa.domain.repository

import com.tinhcd.myesalessfa.domain.DataResult
import com.tinhcd.myesalessfa.domain.model.DraftSurvey
import com.tinhcd.myesalessfa.domain.model.SurveyDefinition

interface SurveyRepository {
    /**
     * A questionnaire, from the local cache so a questionnaire step works with no
     * signal. Null when the server has no active questionnaire for that step.
     *
     * [surveyTypeId] names which one, where the step holds a list — market_info
     * can run several at once. Omitted, the step's first questionnaire is taken,
     * which is the whole story for every step that only ever has one.
     */
    suspend fun definition(
        formId: String,
        surveyTypeId: String? = null,
    ): DataResult<SurveyDefinition?>

    /**
     * Sends [survey]. The server recomputes the score from the question definitions
     * and marks the step done in the same transaction.
     */
    suspend fun submit(survey: DraftSurvey): DataResult<Unit>
}
