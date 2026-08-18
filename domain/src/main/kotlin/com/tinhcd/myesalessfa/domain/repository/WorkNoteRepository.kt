package com.tinhcd.myesalessfa.domain.repository

import com.tinhcd.myesalessfa.domain.DataResult
import com.tinhcd.myesalessfa.domain.model.WorkNote
import com.tinhcd.myesalessfa.domain.model.WorkNoteDraft
import com.tinhcd.myesalessfa.domain.model.WorkNoteStatus

/** The rep's own to-do list. Theirs to write, close and throw away. */
interface WorkNoteRepository {

    /** Null asks for everything, open and closed. */
    suspend fun notes(status: WorkNoteStatus?): DataResult<List<WorkNote>>

    suspend fun add(draft: WorkNoteDraft): DataResult<Unit>

    suspend fun complete(noteId: String, result: String): DataResult<Unit>

    suspend fun delete(noteId: String): DataResult<Unit>
}
