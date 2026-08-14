package com.tinhcd.myesalessfa.data.repository

import android.content.Context
import com.tinhcd.myesalessfa.data.local.ConfigDao
import com.tinhcd.myesalessfa.data.local.OutboxDao
import com.tinhcd.myesalessfa.data.local.OutboxEntity
import com.tinhcd.myesalessfa.data.local.SalesStepEntity
import com.tinhcd.myesalessfa.data.outbox.OrderPayload
import com.tinhcd.myesalessfa.data.outbox.OutboxFlusher
import com.tinhcd.myesalessfa.data.outbox.OutboxWorker
import com.tinhcd.myesalessfa.data.outbox.StepResultPayload
import com.tinhcd.myesalessfa.data.outbox.StockCountPayload
import com.tinhcd.myesalessfa.data.remote.FunctionsService
import com.tinhcd.myesalessfa.domain.DataResult
import com.tinhcd.myesalessfa.domain.model.SalesStep
import com.tinhcd.myesalessfa.domain.model.StepCompletion
import com.tinhcd.myesalessfa.domain.model.SupportedSteps
import com.tinhcd.myesalessfa.domain.model.VisitWorkflow
import com.tinhcd.myesalessfa.domain.model.assembleWorkflow
import com.tinhcd.myesalessfa.domain.repository.ConfigRepository
import com.tinhcd.myesalessfa.domain.repository.SubmitOutcome
import com.tinhcd.myesalessfa.domain.repository.WorkflowRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.Json
import java.time.OffsetDateTime
import java.time.ZoneOffset
import javax.inject.Inject
import javax.inject.Singleton

private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

@Singleton
class WorkflowRepositoryImpl @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val service: FunctionsService,
    private val configDao: ConfigDao,
    private val outboxDao: OutboxDao,
    private val flusher: OutboxFlusher,
    private val configRepository: ConfigRepository,
) : WorkflowRepository {

    override suspend fun workflow(visitId: String): DataResult<VisitWorkflow> = try {
        val definition = configDao.steps().map { it.toDomain() }

        // Server-side completions. Failing to read them is not fatal — the
        // locally queued ones below still count, and a step shown as undone can
        // be redone (the unique constraint makes the write idempotent).
        val remote = runCatching { remoteCompletions(visitId) }.getOrDefault(emptyList())


        // Resolved up front: assembly is a pure function and must not have to
        // reach back into the translation cache per row.
        val titles = definition.associate { it.titleKey to configRepository.translate(it.titleKey) }

        DataResult.Success(
            assembleWorkflow(
                visitId = visitId,
                definition = definition,
                completions = remote + queuedCompletions(),
                titleOf = { titles[it.titleKey] ?: it.titleKey },
                prerequisites = configRepository.stepPrerequisites(),
            ),
        )
    } catch (e: Exception) {
        DataResult.Failure(e.toAppError())
    }

    override suspend fun step(formId: String): DataResult<SalesStep?> = try {
        DataResult.Success(configDao.step(formId)?.toDomain())
    } catch (e: Exception) {
        DataResult.Failure(e.toAppError())
    }

    override suspend fun completeStep(
        visitId: String,
        formId: String,
        payload: Map<String, String>,
    ): DataResult<SubmitOutcome> = try {
        val entry = StepResultPayload(
            visitId = visitId,
            formId = formId,
            completedAt = OffsetDateTime.now(ZoneOffset.UTC).toString(),
            fields = payload,
        )
        outboxDao.insert(
            OutboxEntity(
                type = OutboxEntity.TYPE_STEP_RESULT,
                payload = json.encodeToString(entry),
                createdAt = System.currentTimeMillis(),
            ),
        )

        val drained = runCatching { flusher.flush() }.getOrDefault(false)
        if (drained) {
            DataResult.Success(SubmitOutcome.SENT)
        } else {
            OutboxWorker.enqueue(context)
            DataResult.Success(SubmitOutcome.QUEUED)
        }
    } catch (e: Exception) {
        DataResult.Failure(e.toAppError())
    }

    private suspend fun remoteCompletions(visitId: String): List<StepCompletion> =
        service.visitWorkflow(visitId).completions
            .mapNotNull { dto ->
                dto.completedAt.toEpochMillisOrNull()
                    ?.let { StepCompletion(visitId, dto.formId, it) }
            }

    /**
     * Completions written locally but not yet delivered. Without these a rep who
     * finishes a step in a dead spot sees it flip back to "not done" and stays
     * blocked from checking out.
     *
     * Entries for other visits are handed over as-is; filtering by visit is the
     * assembler's job, so this stays a plain decode.
     */
    private suspend fun queuedCompletions(): List<StepCompletion> =
        queuedStepResults() + queuedOrders() + queuedStockCounts()

    private suspend fun queuedStepResults(): List<StepCompletion> =
        outboxDao.payloadsOfType(OutboxEntity.TYPE_STEP_RESULT)
            .mapNotNull { raw -> runCatching { json.decodeFromString<StepResultPayload>(raw) }.getOrNull() }
            .mapNotNull { payload ->
                payload.completedAt.toEpochMillisOrNull()
                    ?.let { StepCompletion(payload.visitId, payload.formId, it) }
            }

    /**
     * A queued order completes `take_order` too. The server writes that step
     * result inside `submit_order`, so until the order is delivered there is no
     * step result anywhere — and a rep who took an order with no signal would
     * watch the step sit unticked and quite reasonably take it again.
     */
    private suspend fun queuedOrders(): List<StepCompletion> =
        outboxDao.payloadsOfType(OutboxEntity.TYPE_ORDER)
            .mapNotNull { raw -> runCatching { json.decodeFromString<OrderPayload>(raw) }.getOrNull() }
            .mapNotNull { payload ->
                payload.clientCreatedAt.toEpochMillisOrNull()
                    ?.let { StepCompletion(payload.visitId, SupportedSteps.TAKE_ORDER, it) }
            }

    /**
     * A queued count completes `stock_outlet`, for the same reason a queued order
     * completes `take_order` — and with more at stake here, because the order step
     * is gated behind this one. A rep who counted in a dead spot would otherwise
     * be locked out of selling until they got signal back.
     */
    private suspend fun queuedStockCounts(): List<StepCompletion> =
        outboxDao.payloadsOfType(OutboxEntity.TYPE_STOCK_COUNT)
            .mapNotNull { raw ->
                runCatching { json.decodeFromString<StockCountPayload>(raw) }.getOrNull()
            }
            .mapNotNull { payload ->
                payload.clientCreatedAt.toEpochMillisOrNull()
                    ?.let { StepCompletion(payload.visitId, SupportedSteps.STOCK_OUTLET, it) }
            }
}

private fun SalesStepEntity.toDomain() = SalesStep(
    formId = formId,
    order = step,
    titleKey = titleKey,
    isRequired = isRequired,
    config = config.lineSequence()
        .filter { it.contains('=') }
        .associate { line ->
            val i = line.indexOf('=')
            line.substring(0, i) to line.substring(i + 1)
        },
)

/**
 * Null rather than a fallback timestamp: any Long would read as "done", and
 * silently marking a step complete because its timestamp was malformed is the
 * one failure mode here that cannot be recovered from on the device.
 */
private fun String.toEpochMillisOrNull(): Long? =
    runCatching { OffsetDateTime.parse(this).toInstant().toEpochMilli() }.getOrNull()
