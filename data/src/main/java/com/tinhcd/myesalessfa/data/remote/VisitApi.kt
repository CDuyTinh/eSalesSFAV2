package com.tinhcd.myesalessfa.data.remote

import com.tinhcd.myesalessfa.data.remote.http.orThrow
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The three writes the visit flow makes, each a single call to the server.
 *
 * Kept apart from the repositories so the wire shape lives in one place and the
 * repositories stay about behaviour rather than field names.
 */
@Singleton
class VisitApi @Inject constructor(
    private val service: FunctionsService,
) {
    suspend fun insertVisit(visit: NewVisitDto) {
        service.submitVisit(visit).orThrow()
    }

    suspend fun markCheckedOut(visitId: String, checkOutAt: String) {
        service.submitCheckout(
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
        service.submitStep(row).orThrow()
    }
}
