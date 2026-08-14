package com.tinhcd.myesalessfa.data.remote

import com.tinhcd.myesalessfa.data.outbox.DisplayAuditPayload
import com.tinhcd.myesalessfa.data.outbox.OrderPayload
import com.tinhcd.myesalessfa.data.outbox.StockCountPayload
import kotlinx.serialization.json.JsonObject
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

/**
 * Every data call the app makes, as Edge Functions.
 *
 * Nine purposeful endpoints replace seventeen table-shaped ones. The reads are
 * aggregated by what a screen needs rather than by which table the rows live in,
 * so the login flow is one call instead of five and the catalogue is one instead
 * of three.
 *
 * The functions run outside Postgres but build their database client from the
 * caller's own JWT, so RLS still scopes everything exactly as it did when the app
 * spoke to PostgREST. Verified: nvbh02 calling /route gets an empty route rather
 * than nvbh01's customers.
 *
 * Auth is not here. Sign-in, session persistence and token refresh stay with the
 * Supabase SDK; this service borrows the JWT it owns.
 */
interface FunctionsService {

    /**
     * Profile, settings, reason codes, workflow definition and labels in one
     * response. Called after sign-in, before the first check-in can need any of it.
     */
    @GET("bootstrap")
    suspend fun bootstrap(@Query("lang") lang: String): BootstrapDto

    /**
     * Products with their sale units nested, plus the price rules that apply to
     * this rep. The function pages internally, so this is the whole catalogue and
     * not the first page of it.
     */
    @GET("catalogue")
    suspend fun catalogue(): CatalogueDto

    /** Stops with each visit's status already attached. */
    @GET("route")
    suspend fun route(@Query("date") date: String): RouteDto

    /** Server-side step completions. The outbox's own are merged in :domain. */
    @GET("visit-workflow")
    suspend fun visitWorkflow(@Query("visitId") visitId: String): VisitWorkflowDto

    /**
     * The outlet's last stock count, totalled per product in base units.
     * [exceptVisitId] leaves out the visit in progress, so a recount compares
     * against the previous visit rather than the attempt it replaces.
     */
    @GET("previous-count")
    suspend fun previousCount(
        @Query("customerId") customerId: String,
        @Query("exceptVisitId") exceptVisitId: String,
    ): PreviousCountDto

    /**
     * This visit's own count, which is what the order screen measures against par.
     * The inverse of [previousCount], which excludes it.
     */
    @GET("visit-count")
    suspend fun visitCount(@Query("visitId") visitId: String): VisitCountDto

    // -------------------------------------------------------------------------
    // Writes
    //
    // All return Response so the error body survives: a guard inside one of the
    // database functions reports through `message`, and that is what reaches the
    // rep instead of "HTTP 400".
    // -------------------------------------------------------------------------

    /** Check-in. The rep's identity is derived server-side, not sent. */
    @POST("submit-visit")
    suspend fun submitVisit(@Body visit: NewVisitDto): Response<WriteAckDto>

    /**
     * Takes a JsonObject rather than the outbox's own CheckOutPayload: that type is
     * the on-disk format too, and renaming its fields to snake_case would make
     * already-queued entries undecodable.
     */
    @POST("submit-checkout")
    suspend fun submitCheckout(@Body row: JsonObject): Response<WriteAckDto>

    @POST("submit-step")
    suspend fun submitStep(@Body row: JsonObject): Response<WriteAckDto>

    /**
     * Forwards to the `submit_order` database function, which prices the order and
     * writes header, lines and the take_order step in one transaction.
     */
    @POST("submit-order")
    suspend fun submitOrder(@Body order: OrderPayload): Response<WriteAckDto>

    @POST("submit-stock-count")
    suspend fun submitStockCount(@Body count: StockCountPayload): Response<WriteAckDto>

    /**
     * Forwards to `submit_display_audit`, which checks the step's own photo_min and
     * that every storage path it is handed actually exists. The photos must already
     * be uploaded when this is called.
     */
    @POST("submit-display-audit")
    suspend fun submitDisplayAudit(@Body audit: DisplayAuditPayload): Response<WriteAckDto>
}
