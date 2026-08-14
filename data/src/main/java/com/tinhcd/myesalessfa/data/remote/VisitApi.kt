package com.tinhcd.myesalessfa.data.remote

import com.tinhcd.myesalessfa.data.outbox.CheckOutPayload
import com.tinhcd.myesalessfa.data.outbox.StepResultPayload
import com.tinhcd.myesalessfa.data.remote.http.orThrow
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The three writes the visit flow makes. Kept separate from the repositories so
 * the outbox worker can replay them without dragging repository behaviour — and
 * its local-first bookkeeping — into a retry loop.
 */
@Singleton
class VisitApi @Inject constructor(
    private val service: PostgrestService,
) {
    suspend fun insertVisit(visit: NewVisitDto) {
        service.insertVisit(visit).orThrow()
    }

    suspend fun markCheckedOut(payload: CheckOutPayload) {
        service.updateVisit(
            id = Filters.eq(payload.visitId),
            patch = buildJsonObject {
                put("check_out_at", payload.checkOutAt)
                put("status", "completed")
            },
        ).orThrow()
    }

    /**
     * Upsert rather than insert: the outbox may replay an entry that already
     * landed, and a step is either done or not — recording it twice is not a
     * different fact.
     */
    suspend fun saveStepResult(payload: StepResultPayload) {
        val row = buildJsonObject {
            put("visit_id", payload.visitId)
            put("form_id", payload.formId)
            put("completed_at", payload.completedAt)
            put(
                "payload",
                buildJsonObject { payload.fields.forEach { (k, v) -> put(k, v) } },
            )
        }
        service.upsertStepResult(row).orThrow()
    }
}
