package com.tinhcd.myesalessfa.data.repository

import com.tinhcd.myesalessfa.data.remote.dto.NotificationReadPayload
import com.tinhcd.myesalessfa.data.remote.http.orThrow
import com.tinhcd.myesalessfa.data.remote.service.NotificationService
import com.tinhcd.myesalessfa.domain.DataResult
import com.tinhcd.myesalessfa.domain.model.Notification
import com.tinhcd.myesalessfa.domain.model.NotificationFeed
import com.tinhcd.myesalessfa.domain.model.NotificationKind
import com.tinhcd.myesalessfa.domain.repository.NotificationRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NotificationRepositoryImpl @Inject constructor(
    private val service: NotificationService,
) : NotificationRepository {

    override suspend fun feed(
        fromDate: String?,
        toDate: String?,
    ): DataResult<NotificationFeed> = try {
        val body = service.feed(fromDate, toDate).orThrow()

        DataResult.Success(
            NotificationFeed(
                items = body.items.map { row ->
                    Notification(
                        kind = NotificationKind.fromWire(row.kind),
                        sourceId = row.sourceId,
                        title = row.title,
                        body = row.body,
                        code = row.code,
                        fromDate = row.fromDate,
                        toDate = row.toDate,
                        isRead = row.isRead,
                        readAt = row.readAt,
                    )
                },
                unread = body.unread,
            ),
        )
    } catch (e: Exception) {
        DataResult.Failure(e.toAppError())
    }

    /**
     * The badge on its own.
     *
     * It asks for the unread slice rather than a count endpoint of its own — one
     * function, one window, one answer, and the rows it fetches are the ones the
     * badge is counting anyway.
     */
    override suspend fun unreadCount(): DataResult<Int> = try {
        DataResult.Success(service.feed(unreadOnly = true).orThrow().unread)
    } catch (e: Exception) {
        DataResult.Failure(e.toAppError())
    }

    override suspend fun markRead(
        kind: NotificationKind,
        sourceId: String,
    ): DataResult<Int> = try {
        DataResult.Success(
            service.markRead(
                NotificationReadPayload(kind = kind.wire, sourceId = sourceId),
            ).orThrow().unread,
        )
    } catch (e: Exception) {
        DataResult.Failure(e.toAppError())
    }

    override suspend fun markAllRead(
        fromDate: String?,
        toDate: String?,
    ): DataResult<Int> = try {
        DataResult.Success(
            service.markRead(
                NotificationReadPayload(all = true, fromDate = fromDate, toDate = toDate),
            ).orThrow().unread,
        )
    } catch (e: Exception) {
        DataResult.Failure(e.toAppError())
    }
}
