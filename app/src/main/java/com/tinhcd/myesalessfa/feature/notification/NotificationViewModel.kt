package com.tinhcd.myesalessfa.feature.notification

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tinhcd.myesalessfa.domain.DataResult
import com.tinhcd.myesalessfa.domain.model.Notification
import com.tinhcd.myesalessfa.domain.model.NotificationFeed
import com.tinhcd.myesalessfa.domain.model.NotificationKind
import com.tinhcd.myesalessfa.domain.repository.NotificationRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class NotificationUiState(
    val loading: Boolean = true,
    val feed: NotificationFeed = NotificationFeed(),
    /** Null is "tất cả". */
    val filter: NotificationKind? = null,
    val unreadOnly: Boolean = false,
    val working: Boolean = false,
    val error: String? = null,
) {
    /** What the list shows: the kind filter and the unread switch, in that order. */
    val visible: List<Notification>
        get() = feed.of(filter).let { rows ->
            if (unreadOnly) rows.filter { !it.isRead } else rows
        }

    val canMarkAll: Boolean get() = !working && feed.unread > 0
}

/**
 * The bell.
 *
 * The legacy screen is a list with a type filter, a read/unread filter, a badge
 * and a "mark all". This is the same four things — what differs is that the list
 * is assembled server-side out of the programme tables rather than read from a
 * notifications table, which the rep cannot tell apart and should not have to.
 */
@HiltViewModel
class NotificationViewModel @Inject constructor(
    private val repository: NotificationRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(NotificationUiState())
    val state: StateFlow<NotificationUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            when (val result = repository.feed()) {
                is DataResult.Success -> _state.update {
                    it.copy(loading = false, feed = result.data)
                }

                is DataResult.Failure -> _state.update {
                    it.copy(loading = false, error = "Không tải được thông báo")
                }
            }
        }
    }

    fun onFilter(kind: NotificationKind?) = _state.update {
        it.copy(filter = if (it.filter == kind) null else kind, error = null)
    }

    fun onUnreadOnly(value: Boolean) = _state.update {
        it.copy(unreadOnly = value, error = null)
    }

    /**
     * Opening an item reads it.
     *
     * The list is updated straight away and the call goes out behind it, because
     * the rep's next move is to come back to a list that has already moved on.
     * A failed call leaves the badge one too low until the next load, which is
     * the cheaper of the two wrong answers: the alternative is a row that flicks
     * back to unread under a rep who just read it.
     */
    fun onOpen(item: Notification) {
        if (item.isRead) return
        _state.update { it.copy(feed = it.feed.withRead(item.key)) }

        viewModelScope.launch {
            when (val result = repository.markRead(item.kind, item.sourceId)) {
                is DataResult.Success -> _state.update {
                    it.copy(feed = it.feed.copy(unread = result.data))
                }

                is DataResult.Failure -> Unit
            }
        }
    }

    fun onMarkAll() {
        if (!_state.value.canMarkAll) return
        _state.update { it.copy(working = true, error = null) }

        viewModelScope.launch {
            when (repository.markAllRead()) {
                is DataResult.Success -> _state.update {
                    it.copy(working = false, feed = it.feed.withAllRead())
                }

                is DataResult.Failure -> _state.update {
                    it.copy(working = false, error = "Không đánh dấu được đã đọc")
                }
            }
        }
    }
}
