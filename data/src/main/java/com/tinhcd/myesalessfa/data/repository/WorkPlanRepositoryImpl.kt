package com.tinhcd.myesalessfa.data.repository

import com.tinhcd.myesalessfa.data.remote.dto.WorkPlanCommandPayload
import com.tinhcd.myesalessfa.data.remote.dto.WorkPlanDetailDto
import com.tinhcd.myesalessfa.data.remote.dto.WorkPlanDto
import com.tinhcd.myesalessfa.data.remote.http.orThrow
import com.tinhcd.myesalessfa.data.remote.service.WorkPlanService
import com.tinhcd.myesalessfa.domain.DataResult
import com.tinhcd.myesalessfa.domain.model.DraftWorkPlan
import com.tinhcd.myesalessfa.domain.model.WorkPlan
import com.tinhcd.myesalessfa.domain.model.WorkPlanBoard
import com.tinhcd.myesalessfa.domain.model.WorkPlanDay
import com.tinhcd.myesalessfa.domain.model.WorkPlanDetail
import com.tinhcd.myesalessfa.domain.model.WorkPlanEvent
import com.tinhcd.myesalessfa.domain.model.WorkPlanObject
import com.tinhcd.myesalessfa.domain.model.WorkPlanObjectKind
import com.tinhcd.myesalessfa.domain.model.WorkPlanStatus
import com.tinhcd.myesalessfa.domain.model.WorkWithCategory
import com.tinhcd.myesalessfa.domain.repository.WorkPlanRepository
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WorkPlanRepositoryImpl @Inject constructor(
    private val service: WorkPlanService,
) : WorkPlanRepository {

    override suspend fun board(
        fromDate: String?,
        toDate: String?,
        statuses: List<WorkPlanStatus>,
        assignedToMe: Boolean?,
    ): DataResult<WorkPlanBoard> = try {
        val body = service.board(
            fromDate = fromDate,
            toDate = toDate,
            statuses = statuses.takeIf { it.isNotEmpty() }?.joinToString(",") { it.wire },
            assignedToMe = assignedToMe,
        ).orThrow()

        DataResult.Success(
            WorkPlanBoard(
                items = body.items.map { it.toDomain() },
                // An unknown status name is dropped rather than mapped onto a
                // real one: a wrong count is worse than a missing chip.
                counts = body.counts.mapNotNull { (wire, n) ->
                    WorkPlanStatus.entries.firstOrNull { it.wire == wire }?.let { it to n }
                }.toMap(),
                calendar = body.calendar.map {
                    WorkPlanDay(
                        day = it.day,
                        status = WorkPlanStatus.fromWire(it.status),
                        planCount = it.planCount,
                    )
                },
                categories = body.categories.map {
                    WorkWithCategory(
                        id = it.id,
                        code = it.code,
                        name = it.name,
                        fieldTraining = it.fieldTraining,
                        tradeProgram = it.tradeProgram,
                        objectKind = WorkPlanObjectKind.fromWire(it.objectKind),
                        minSales = it.minSales,
                        maxSales = it.maxSales,
                    )
                },
                total = body.total,
            ),
        )
    } catch (e: Exception) {
        DataResult.Failure(e.toAppError())
    }

    override suspend fun detail(planId: String): DataResult<WorkPlanDetail> = try {
        DataResult.Success(service.detail(planId).orThrow().plan.toDomain())
    } catch (e: Exception) {
        DataResult.Failure(e.toAppError())
    }

    override suspend fun save(draft: DraftWorkPlan): DataResult<WorkPlanDetail> = try {
        val category = requireNotNull(draft.category) { "a work type is required" }

        val plan = buildMap<String, kotlinx.serialization.json.JsonElement> {
            draft.id?.let { put("id", JsonPrimitive(it)) }
            put("category_id", JsonPrimitive(category.id))
            put("content", JsonPrimitive(draft.content.trim()))
            put("note", draft.note.trim().ifBlank { null }?.let(::JsonPrimitive) ?: JsonNull)
            put("place", draft.place.trim().ifBlank { null }?.let(::JsonPrimitive) ?: JsonNull)
            put("from_at", JsonPrimitive(draft.fromAt))
            put("to_at", JsonPrimitive(draft.toAt))
            put(
                "objects",
                JsonArray(
                    draft.objects.map {
                        JsonObject(mapOf("id" to JsonPrimitive(it.targetId)))
                    },
                ),
            )
        }

        val ack = service.command(
            WorkPlanCommandPayload(action = "save", plan = JsonObject(plan)),
        ).orThrow()

        DataResult.Success(
            requireNotNull(ack.plan) { "the server saved nothing" }.toDomain(),
        )
    } catch (e: Exception) {
        DataResult.Failure(e.toAppError())
    }

    override suspend fun setStatus(
        planId: String,
        status: WorkPlanStatus,
        reason: String?,
    ): DataResult<WorkPlanDetail> = try {
        val ack = service.command(
            WorkPlanCommandPayload(
                action = "status",
                planId = planId,
                status = status.wire,
                reason = reason?.trim()?.ifBlank { null },
            ),
        ).orThrow()

        DataResult.Success(
            requireNotNull(ack.plan) { "the server returned no plan" }.toDomain(),
        )
    } catch (e: Exception) {
        DataResult.Failure(e.toAppError())
    }

    override suspend fun plannableCustomers(query: String): DataResult<List<WorkPlanObject>> = try {
        DataResult.Success(
            service.plannableCustomers(query).orThrow().customers.map {
                WorkPlanObject(
                    kind = WorkPlanObjectKind.CUSTOMER,
                    targetId = it.id,
                    name = it.name,
                    code = it.code,
                    address = it.address,
                )
            },
        )
    } catch (e: Exception) {
        DataResult.Failure(e.toAppError())
    }

    override suspend fun delete(planId: String): DataResult<Unit> = try {
        service.command(
            WorkPlanCommandPayload(action = "delete", planId = planId),
        ).orThrow()
        DataResult.Success(Unit)
    } catch (e: Exception) {
        DataResult.Failure(e.toAppError())
    }
}

private fun WorkPlanDto.toDomain() = WorkPlan(
    id = id,
    categoryId = categoryId,
    categoryCode = categoryCode,
    categoryName = categoryName,
    fieldTraining = fieldTraining,
    objectKind = WorkPlanObjectKind.fromWire(objectKind),
    content = content,
    note = note,
    place = place,
    fromAt = fromAt,
    toAt = toAt,
    status = WorkPlanStatus.fromWire(status),
    result = result,
    reason = reason,
    assignedToMe = assignedToMe,
    objectCount = objectCount,
)

private fun WorkPlanDetailDto.toDomain() = WorkPlanDetail(
    plan = WorkPlan(
        id = id,
        categoryId = categoryId,
        categoryCode = categoryCode,
        categoryName = categoryName,
        fieldTraining = fieldTraining,
        objectKind = WorkPlanObjectKind.fromWire(objectKind),
        content = content,
        note = note,
        place = place,
        fromAt = fromAt,
        toAt = toAt,
        status = WorkPlanStatus.fromWire(status),
        result = result,
        reason = reason,
        assignedToMe = assignedToMe,
        objectCount = objects.size,
    ),
    objects = objects.mapNotNull { row ->
        // A row whose kind this build does not know is dropped: showing it with
        // a guessed kind would let the rep act on the wrong thing.
        WorkPlanObjectKind.fromWire(row.kind)?.let { kind ->
            WorkPlanObject(
                kind = kind,
                targetId = row.targetId,
                name = row.name.orEmpty(),
                code = row.code,
                address = row.address,
            )
        }
    },
    history = history.map {
        WorkPlanEvent(
            status = WorkPlanStatus.fromWire(it.status),
            at = it.at,
            actor = it.actor,
            reason = it.reason,
        )
    },
    photos = photos.map { it.storagePath },
    tradeProgram = tradeProgram,
    submittedAt = submittedAt,
    approvedAt = approvedAt,
    rejectedAt = rejectedAt,
    completedAt = completedAt,
    cancelledAt = cancelledAt,
)
