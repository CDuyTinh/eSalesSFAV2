package com.tinhcd.myesalessfa.data.remote.service

import com.tinhcd.myesalessfa.data.remote.dto.CompleteWorkNoteDto
import com.tinhcd.myesalessfa.data.remote.dto.NewWorkNoteDto
import com.tinhcd.myesalessfa.data.remote.dto.WorkNotesDto
import com.tinhcd.myesalessfa.data.remote.dto.WriteAckDto
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Query

interface WorkNoteService {

    @GET("work-notes")
    suspend fun notes(@Query("status") status: String?): Response<WorkNotesDto>

    @POST("work-notes")
    suspend fun add(@Body note: NewWorkNoteDto): Response<WriteAckDto>

    @PATCH("work-notes")
    suspend fun complete(@Body completion: CompleteWorkNoteDto): Response<WriteAckDto>

    @DELETE("work-notes")
    suspend fun delete(@Query("id") noteId: String): Response<WriteAckDto>
}
