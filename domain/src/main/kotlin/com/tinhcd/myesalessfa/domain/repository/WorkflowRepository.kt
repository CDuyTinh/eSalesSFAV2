package com.tinhcd.myesalessfa.domain.repository

import com.tinhcd.myesalessfa.domain.DataResult
import com.tinhcd.myesalessfa.domain.model.SalesStep
import com.tinhcd.myesalessfa.domain.model.VisitWorkflow

interface WorkflowRepository {
    /**
     * The configured steps for this visit, merged with what the rep has already
     * completed. The definition comes from the local cache; the completions come
     * from the server.
     */
    suspend fun workflow(visitId: String): DataResult<VisitWorkflow>

    /**
     * The definition of a single step, so its screen can read its own label and
     * config instead of assuming either. Null when the server has never sent a
     * step with this [formId].
     */
    suspend fun step(formId: String): DataResult<SalesStep?>

    suspend fun completeStep(
        visitId: String,
        formId: String,
        payload: Map<String, String>,
    ): DataResult<Unit>
}
