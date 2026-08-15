package com.tinhcd.myesalessfa.data.repository

import com.tinhcd.myesalessfa.data.local.ConfigDao
import com.tinhcd.myesalessfa.data.local.SalesStepEntity
import com.tinhcd.myesalessfa.data.remote.service.WorkflowService
import com.tinhcd.myesalessfa.data.remote.api.VisitApi
import com.tinhcd.myesalessfa.domain.DataResult
import com.tinhcd.myesalessfa.domain.model.SalesStep
import com.tinhcd.myesalessfa.domain.model.StepCompletion
import com.tinhcd.myesalessfa.domain.model.VisitWorkflow
import com.tinhcd.myesalessfa.domain.model.assembleWorkflow
import com.tinhcd.myesalessfa.domain.repository.ConfigRepository
import com.tinhcd.myesalessfa.domain.repository.WorkflowRepository
import java.time.OffsetDateTime
import java.time.ZoneOffset
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WorkflowRepositoryImpl @Inject constructor(
    private val service: WorkflowService,
    private val visitApi: VisitApi,
    private val configDao: ConfigDao,
    private val configRepository: ConfigRepository,
) : WorkflowRepository {

    /**
     * The step list comes from the local cache — it is configuration, read on every
     * visit and changed by head office now and then. What the rep has finished comes
     * from the server, which is the only place it is recorded.
     */
    override suspend fun workflow(visitId: String): DataResult<VisitWorkflow> = try {
        val definition = configDao.steps().map { it.toDomain() }

        // Resolved up front: assembly is a pure function and must not have to
        // reach back into the translation cache per row.
        val titles = definition.associate { it.titleKey to configRepository.translate(it.titleKey) }

        DataResult.Success(
            assembleWorkflow(
                visitId = visitId,
                definition = definition,
                completions = remoteCompletions(visitId),
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
    ): DataResult<Unit> = try {
        visitApi.saveStepResult(
            visitId = visitId,
            formId = formId,
            completedAt = OffsetDateTime.now(ZoneOffset.UTC).toString(),
            fields = payload,
        )
        DataResult.Success(Unit)
    } catch (e: Exception) {
        DataResult.Failure(e.toAppError())
    }

    private suspend fun remoteCompletions(visitId: String): List<StepCompletion> =
        service.visitWorkflow(visitId).completions
            .mapNotNull { dto ->
                dto.completedAt.toEpochMillisOrNull()
                    ?.let { StepCompletion(visitId, dto.formId, it) }
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
