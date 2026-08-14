package com.tinhcd.myesalessfa.data.remote

import com.tinhcd.myesalessfa.data.outbox.StockCountPayload
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import javax.inject.Inject
import javax.inject.Singleton

private val json = Json { encodeDefaults = true; explicitNulls = false }

private const val PREVIOUS_COUNT_COLUMNS =
    "id,count_date,lines:stock_count_line(product_id,uom_code,qty,base_qty)"

/**
 * Stock counting.
 *
 * `submit_stock_count` writes the header, the lines and the `stock_outlet` step
 * in one transaction, fills each line's previous figure from the customer's last
 * count, and is idempotent on the client-minted id so an outbox replay is a
 * no-op.
 */
@Singleton
class StockApi @Inject constructor(
    private val client: SupabaseClient,
) {
    suspend fun submit(payload: StockCountPayload) {
        client.postgrest.rpc(
            function = "submit_stock_count",
            parameters = buildJsonObject {
                put("p_count", json.encodeToJsonElement(payload))
            },
        )
    }

    /**
     * The customer's most recent count, so the rep sees "last time" beside each
     * product while counting rather than only after submitting.
     *
     * Excludes the visit in progress: on a recount, the figure worth comparing
     * against is the previous visit's, not the attempt being replaced. This
     * mirrors what the RPC stores.
     */
    suspend fun previousCount(customerId: String, exceptVisitId: String): PreviousCountDto? =
        client.from("stock_count")
            .select(Columns.raw(PREVIOUS_COUNT_COLUMNS)) {
                filter {
                    eq("customer_id", customerId)
                    neq("visit_id", exceptVisitId)
                }
                order("count_date", Order.DESCENDING)
                order("created_at", Order.DESCENDING)
                limit(1)
            }
            .decodeList<PreviousCountDto>()
            .firstOrNull()
}
