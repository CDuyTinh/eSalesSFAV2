package com.tinhcd.myesalessfa.data.remote

import com.tinhcd.myesalessfa.data.outbox.CheckOutPayload
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
}
