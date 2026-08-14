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
    private val service: FunctionsService,
) {
    suspend fun insertVisit(visit: NewVisitDto) {
        service.submitVisit(visit).orThrow()
    }

    /**
     * The payload is rebuilt rather than sent as-is. [CheckOutPayload] is also the
     * outbox's on-disk format, and renaming its fields to match the wire would make
     * every already-queued entry undecodable — a queued check-out would then retry
     * forever instead of being delivered.
     */
    suspend fun markCheckedOut(payload: CheckOutPayload) {
        service.submitCheckout(
            buildJsonObject {
                put("visit_id", payload.visitId)
                put("check_out_at", payload.checkOutAt)
            },
        ).orThrow()
    }

    /**
     * Upsert on the server side: the outbox may replay an entry that already
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
        service.submitStep(row).orThrow()
    }
}
