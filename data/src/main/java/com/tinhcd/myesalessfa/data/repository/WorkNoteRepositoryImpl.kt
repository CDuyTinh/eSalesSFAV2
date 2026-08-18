package com.tinhcd.myesalessfa.data.repository

import com.tinhcd.myesalessfa.data.remote.dto.CompleteWorkNoteDto
import com.tinhcd.myesalessfa.data.remote.dto.NewWorkNoteDto
import com.tinhcd.myesalessfa.data.remote.dto.WorkNoteDto
import com.tinhcd.myesalessfa.data.remote.http.orThrow
import com.tinhcd.myesalessfa.data.remote.service.WorkNoteService
import com.tinhcd.myesalessfa.domain.DataResult
import com.tinhcd.myesalessfa.domain.model.WorkNote
import com.tinhcd.myesalessfa.domain.model.WorkNoteDraft
import com.tinhcd.myesalessfa.domain.model.WorkNoteStatus
import com.tinhcd.myesalessfa.domain.repository.WorkNoteRepository
import java.time.LocalDate
import java.time.OffsetDateTime
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WorkNoteRepositoryImpl @Inject constructor(
    private val service: WorkNoteService,
) : WorkNoteRepository {

    override suspend fun notes(status: WorkNoteStatus?): DataResult<List<WorkNote>> = try {
        val wire = status?.let { if (it == WorkNoteStatus.OPEN) "open" else "done" }
        DataResult.Success(service.notes(wire).orThrow().notes.map { it.toDomain() })
    } catch (e: Exception) {
        DataResult.Failure(e.toAppError())
    }

    override suspend fun add(draft: WorkNoteDraft): DataResult<Unit> = try {
        service.add(
            NewWorkNoteDto(
                title = draft.title.trim(),
                body = draft.body.trim().ifBlank { null },
                dueOn = draft.dueOn?.toString(),
                customerId = draft.customerId,
            ),
        ).orThrow()
        DataResult.Success(Unit)
    } catch (e: Exception) {
        DataResult.Failure(e.toAppError())
    }

    override suspend fun complete(noteId: String, result: String): DataResult<Unit> = try {
        service.complete(
            CompleteWorkNoteDto(noteId = noteId, result = result.trim()),
        ).orThrow()
        DataResult.Success(Unit)
    } catch (e: Exception) {
        DataResult.Failure(e.toAppError())
    }

    override suspend fun delete(noteId: String): DataResult<Unit> = try {
        service.delete(noteId).orThrow()
        DataResult.Success(Unit)
    } catch (e: Exception) {
        DataResult.Failure(e.toAppError())
    }
}

private fun WorkNoteDto.toDomain() = WorkNote(
    noteId = noteId,
    title = title,
    body = body,
    dueOn = dueOn?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
    status = if (status == "done") WorkNoteStatus.DONE else WorkNoteStatus.OPEN,
    result = result,
    doneAtEpochMs = doneAt?.toEpochMillisOrNull(),
    createdAtEpochMs = createdAt?.toEpochMillisOrNull(),
    customerId = customerId,
    customerName = customerName,
)

private fun String.toEpochMillisOrNull(): Long? =
    runCatching { OffsetDateTime.parse(this).toInstant().toEpochMilli() }.getOrNull()
