package com.tinhcd.myesalessfa.data.remote.api

import com.tinhcd.myesalessfa.data.remote.dto.NewVisitDto
import com.tinhcd.myesalessfa.data.remote.http.orThrow
import com.tinhcd.myesalessfa.data.remote.service.VisitService
import com.tinhcd.myesalessfa.data.remote.service.WorkflowService
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The three writes the visit flow makes, each a single call to the server.
 *
 * Kept apart from the repositories so the wire shape lives in one place and the
 * repositories stay about behaviour rather than field names.
 *
 * Two services, because a step result is a workflow fact rather than part of the
 * visit's own lifecycle — the same split the repositories make.
 */
@Singleton
class VisitApi @Inject constructor(
    private val visitService: VisitService,
    private val workflowService: WorkflowService,
) {
    suspend fun insertVisit(visit: NewVisitDto) {
        visitService.submitVisit(visit).orThrow()
    }

    suspend fun markCheckedOut(visitId: String, checkOutAt: String) {
        visitService.submitCheckout(
            buildJsonObject {
                put("visit_id", visitId)
                put("check_out_at", checkOutAt)
            },
        ).orThrow()
    }

    /**
     * Upserted server side: a rep may redo a step during the same visit, and a
     * step is either done or not — recording it twice is not a different fact.
     */
    suspend fun saveStepResult(
        visitId: String,
        formId: String,
        completedAt: String,
        fields: Map<String, String>,
    ) {
        val row = buildJsonObject {
            put("visit_id", visitId)
            put("form_id", formId)
            put("completed_at", completedAt)
            put("payload", buildJsonObject { fields.forEach { (k, v) -> put(k, v) } })
        }
        workflowService.submitStep(row).orThrow()
    }
}
