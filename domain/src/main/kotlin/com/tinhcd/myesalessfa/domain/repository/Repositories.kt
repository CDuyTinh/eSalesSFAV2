package com.tinhcd.myesalessfa.domain.repository

import com.tinhcd.myesalessfa.domain.DataResult
import com.tinhcd.myesalessfa.domain.model.CheckInPolicy
import com.tinhcd.myesalessfa.domain.model.CheckInRequest
import com.tinhcd.myesalessfa.domain.model.DayRoute
import com.tinhcd.myesalessfa.domain.model.DraftDisplayAudit
import com.tinhcd.myesalessfa.domain.model.DraftFeedback
import com.tinhcd.myesalessfa.domain.model.DraftOrder
import com.tinhcd.myesalessfa.domain.model.DraftStockCount
import com.tinhcd.myesalessfa.domain.model.DraftSurvey
import com.tinhcd.myesalessfa.domain.model.PricedProduct
import com.tinhcd.myesalessfa.domain.model.ReasonCode
import com.tinhcd.myesalessfa.domain.model.ReasonKind
import com.tinhcd.myesalessfa.domain.model.RouteStop
import com.tinhcd.myesalessfa.domain.model.SalesStep
import com.tinhcd.myesalessfa.domain.model.Salesperson
import com.tinhcd.myesalessfa.domain.model.SessionState
import com.tinhcd.myesalessfa.domain.model.SurveyDefinition
import com.tinhcd.myesalessfa.domain.model.SyncState
import com.tinhcd.myesalessfa.domain.model.VisitWorkflow
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

interface AuthRepository {
    /**
     * Whether there is a session, and the rep's profile when it has been loaded.
     * Emits nothing until the stored session has been resolved one way or the
     * other, so a collector taking `first()` never acts on "not known yet".
     */
    val sessionState: Flow<SessionState>

    /**
     * The current rep, or null. Null is ambiguous by nature — signed out, or signed
     * in without a profile — so anything that has to tell those apart wants
     * [sessionState] instead. This stays for the callers that only need a name.
     */
    val currentUser: Flow<Salesperson?>

    /**
     * @param username the code the rep types, e.g. "nvbh01". Mapping it onto
     * whatever the auth backend wants is this layer's problem, not the UI's.
     */
    suspend fun signIn(username: String, password: String): DataResult<Salesperson>

    /**
     * Re-fetches the profile for a session that already exists, for the case where
     * the fetch at sign-in or at launch failed for its own reasons. Updates
     * [sessionState] on success.
     */
    suspend fun refreshProfile(): DataResult<Salesperson>

    suspend fun signOut(): DataResult<Unit>
}

interface RouteRepository {
    /**
     * Stops scheduled for [date], in visit order.
     *
     * Falls back to the copy the device kept when the server cannot be reached, so a
     * rep standing in a shop with no bars can still see which outlet they are in and
     * check into it. Only a date that has never been fetched can fail outright.
     */
    suspend fun getRoute(date: LocalDate): DataResult<DayRoute>

    suspend fun getStop(customerId: String, date: LocalDate): DataResult<RouteStop?>
}

/** Outcome of handing a check-in to the data layer. */
enum class SubmitOutcome {
    /** Reached the server. */
    SENT,

    /** Stored locally; the outbox worker will retry. */
    QUEUED,
}

interface CheckInRepository {
    /**
     * Records a check-in. Succeeds even with no connection: the entry is
     * queued locally and flushed later, because a rep standing in a shop with
     * one bar of signal must not lose the visit.
     */
    suspend fun checkIn(request: CheckInRequest): DataResult<SubmitOutcome>

    suspend fun checkOut(visitId: String): DataResult<SubmitOutcome>

    /** Entries still waiting to reach the server. */
    val pendingCount: Flow<Int>
}

interface ConfigRepository {
    suspend fun checkInPolicy(): CheckInPolicy

    suspend fun reasons(kind: ReasonKind): List<ReasonCode>

    /** Translated label for [key], falling back to [key] itself. */
    suspend fun translate(key: String): String

    /**
     * Step form id -> the step that must be completed before it opens, derived
     * from settings. Empty when the market imposes no ordering between steps.
     */
    suspend fun stepPrerequisites(): Map<String, String>

    /** Pulls settings, reason codes, workflow steps and translations locally. */
    suspend fun refresh(): DataResult<Unit>
}

/**
 * Keeps the cached reference data current.
 *
 * It used to be refreshed in exactly one place — the sign-in screen — so a rep who
 * stayed signed in never saw a change head office made. That is not hypothetical: a
 * step's own `note_min_length` and a set of new feedback topics were each invisible on
 * a real device until someone signed out and back in, and both times the app was
 * confidently rendering stale rules.
 *
 * Refreshing is never allowed to matter to whoever asked for it. Every screen reads
 * from the cache, the cache is only replaced on success, and a rep inside a shop with
 * no signal must not be told that something is wrong when nothing is.
 */
interface ReferenceDataSync {
    val state: Flow<SyncState>

    /**
     * Refreshes if the cache has not been refreshed yet today, and does nothing
     * otherwise. Returns immediately — the work continues in the background, because
     * the caller is a lifecycle callback, not somebody waiting for an answer.
     */
    fun syncIfStale()

    /**
     * Refreshes now, whatever the cache's age, and waits for the outcome. For
     * sign-in, which must not hand a rep an app with no catalogue, and for the rep's
     * own refresh action, which exists precisely for when they have been told
     * something changed.
     */
    suspend fun syncNow(): DataResult<Unit>
}

interface CatalogRepository {
    /** Pulls products, sale units and the price list into the local cache. */
    suspend fun refresh(): DataResult<Unit>

    /**
     * The catalogue as this customer may buy it: units they have no price for are
     * not offered, because the server refuses to book an unpriced line.
     *
     * @param on the order date, since prices are effective-dated.
     */
    suspend fun catalogue(
        classId: String?,
        on: LocalDate,
    ): DataResult<List<PricedProduct>>

    /**
     * Par levels this outlet is obliged to stock, as product id -> base units.
     *
     * Resolved from the cached lists rather than fetched per customer, so the stock
     * screen can mark required SKUs with no signal. Empty when no list covers the
     * outlet's segment.
     */
    suspend fun mustStock(
        channelId: String?,
        shopTypeId: String?,
        on: LocalDate,
    ): DataResult<Map<String, Int>>
}

interface StockRepository {
    /**
     * This product's base-unit total at the customer's previous count, keyed by
     * product id. Empty when the outlet has never been counted.
     *
     * Excludes [visitId] so a recount compares against the previous visit rather
     * than against the attempt it is replacing.
     */
    suspend fun previousCount(customerId: String, visitId: String): DataResult<Map<String, Int>>

    /**
     * What this visit's count found, as product id -> base units.
     *
     * Includes a count still sitting in the outbox: a rep who counted in a dead
     * spot must still get order suggestions, and the server has not heard about it
     * yet. Empty when nothing has been counted on this visit.
     */
    suspend fun countedBaseQty(visitId: String): DataResult<Map<String, Int>>

    /**
     * Queues [count] for delivery. The server fills each line's previous figure
     * and marks the `stock_outlet` step done in the same transaction.
     */
    suspend fun submit(count: DraftStockCount): DataResult<SubmitOutcome>
}

interface SurveyRepository {
    /**
     * The questionnaire configured for [formId], from the local cache so a
     * questionnaire step works with no signal. Null when the server has no active
     * questionnaire for that step.
     */
    suspend fun definition(formId: String): DataResult<SurveyDefinition?>

    /**
     * Queues [survey]. The server recomputes the score from the question definitions
     * and marks the step done in the same transaction.
     */
    suspend fun submit(survey: DraftSurvey): DataResult<SubmitOutcome>
}

interface DisplayAuditRepository {
    /**
     * Queues [audit] for delivery, photos and all. The photos are uploaded first and
     * the row written second, so a delivered audit always has its evidence behind it.
     */
    suspend fun submit(audit: DraftDisplayAudit): DataResult<SubmitOutcome>
}

interface FeedbackRepository {
    /**
     * Queues [feedback] for delivery, audio and all. Any recording is uploaded first
     * and the row written second, so a delivered row always has its audio behind it.
     * The server marks the `feedback` step done in the same transaction.
     */
    suspend fun submit(feedback: DraftFeedback): DataResult<SubmitOutcome>
}

interface OrderRepository {
    /**
     * Queues [order] for delivery. The server prices it and marks the
     * `take_order` step done in the same transaction, so a delivered order can
     * never leave the rep still owing the step.
     */
    suspend fun submit(order: DraftOrder): DataResult<SubmitOutcome>
}

interface WorkflowRepository {
    /**
     * The configured steps for this visit, merged with what the rep has already
     * completed — including completions still sitting in the outbox, so a step
     * finished without signal does not appear undone.
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
    ): DataResult<SubmitOutcome>
}
