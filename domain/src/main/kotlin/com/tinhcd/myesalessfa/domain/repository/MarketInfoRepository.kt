package com.tinhcd.myesalessfa.domain.repository

import com.tinhcd.myesalessfa.domain.DataResult
import com.tinhcd.myesalessfa.domain.model.DraftCompetitorSurvey
import com.tinhcd.myesalessfa.domain.model.MarketInfoSurvey

interface MarketInfoRepository {

    /**
     * Every survey this outlet owes today, of both kinds, with whether this visit
     * has finished it. Empty is a real answer: a branch running no surveys leaves
     * the step with nothing to do.
     */
    suspend fun surveys(customerId: String, visitId: String): DataResult<List<MarketInfoSurvey>>

    /**
     * One competitor survey with its criteria, its pairings, and whatever this
     * visit already answered — so reopening one shows the earlier answers rather
     * than a blank grid.
     */
    suspend fun competitorSurvey(
        surveyId: String,
        visitId: String,
    ): DataResult<DraftCompetitorSurvey>

    /**
     * Sends the whole grid at once. Photos are uploaded first and the rows written
     * second, so a stored answer always has its evidence behind it.
     */
    suspend fun submit(draft: DraftCompetitorSurvey): DataResult<Unit>
}
