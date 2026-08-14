package com.tinhcd.myesalessfa.data.remote

import com.tinhcd.myesalessfa.data.outbox.CheckOutPayload
import com.tinhcd.myesalessfa.data.outbox.StepResultPayload
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.from
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The two writes the check-in flow makes. Kept separate from the repository so
 * the outbox worker can replay them without dragging the repository — and its
 * local-first behaviour — into a retry loop.
 */
@Singleton
class VisitApi @Inject constructor(
    private val client: SupabaseClient,
) {
    suspend fun insertVisit(visit: NewVisitDto) {
        client.from("visit").insert(visit)
    }

    suspend fun markCheckedOut(payload: CheckOutPayload) {
        client.from("visit").update(
            buildJsonObject {
                put("check_out_at", payload.checkOutAt)
                put("status", "completed")
            },
        ) {
            filter { eq("id", payload.visitId) }
        }
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
        client.from("visit_step_result").upsert(row) {
            onConflict = "visit_id,form_id"
        }
    }
}
