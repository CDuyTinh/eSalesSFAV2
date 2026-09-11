package com.tinhcd.myesalessfa.domain.repository

import com.tinhcd.myesalessfa.domain.DataResult
import com.tinhcd.myesalessfa.domain.model.NotificationFeed
import com.tinhcd.myesalessfa.domain.model.NotificationKind

interface NotificationRepository {

    /** Everything published for this rep in the window, with the unread count. */
    suspend fun feed(fromDate: String? = null, toDate: String? = null): DataResult<NotificationFeed>

    /** Just the badge, for screens that show the number without the list. */
    suspend fun unreadCount(): DataResult<Int>

    suspend fun markRead(kind: NotificationKind, sourceId: String): DataResult<Int>

    suspend fun markAllRead(fromDate: String? = null, toDate: String? = null): DataResult<Int>
}
