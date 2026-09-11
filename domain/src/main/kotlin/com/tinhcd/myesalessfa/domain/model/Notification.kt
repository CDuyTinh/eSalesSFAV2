package com.tinhcd.myesalessfa.domain.model

/**
 * What a notification is about.
 *
 * Not a message type but a pointer: every item in the bell is a row in one of
 * six other tables, and this says which. The screen uses it to label the item
 * and to decide where tapping it leads.
 */
enum class NotificationKind(val wire: String, val label: String) {
    PROMOTION("promotion", "Khuyến mãi"),
    MANUAL_PROMOTION("manual_promotion", "Khuyến mãi tay"),
    DISPLAY("display", "Trưng bày"),
    LOYALTY("loyalty", "Tích lũy"),
    POSM("posm", "POSM"),
    WORK_NOTE("work_note", "Ghi chú");

    companion object {
        /**
         * Unknown kinds fall back to [PROMOTION] rather than throwing: the server
         * may grow a seventh feed before this build knows about it, and a rep
         * mid-round should see an odd label, not a crash.
         */
        fun fromWire(value: String): NotificationKind =
            entries.firstOrNull { it.wire == value } ?: PROMOTION
    }
}

/** One line in the bell. */
data class Notification(
    val kind: NotificationKind,
    /** The id of the programme or note this is about. */
    val sourceId: String,
    val title: String,
    val body: String,
    val code: String? = null,
    val fromDate: String? = null,
    val toDate: String? = null,
    val isRead: Boolean = false,
    val readAt: String? = null,
) {
    /** A stable key for the list, since the id alone repeats across kinds. */
    val key: String get() = "${kind.wire}:$sourceId"
}

/**
 * The bell's whole state: the list and the number on the badge.
 *
 * [unread] is counted over the window rather than over [items] because the list
 * may be filtered to one kind while the badge still counts everything.
 */
data class NotificationFeed(
    val items: List<Notification> = emptyList(),
    val unread: Int = 0,
) {
    /** Narrowed to one kind, or all of them when [kind] is null. */
    fun of(kind: NotificationKind?): List<Notification> =
        if (kind == null) items else items.filter { it.kind == kind }

    /** Which kinds are actually present, for the filter row. */
    val presentKinds: List<NotificationKind>
        get() = NotificationKind.entries.filter { kind -> items.any { it.kind == kind } }

    /** Marks one item read locally, so the list does not wait for a reload. */
    fun withRead(key: String): NotificationFeed {
        val target = items.firstOrNull { it.key == key } ?: return this
        if (target.isRead) return this
        return copy(
            items = items.map { if (it.key == key) it.copy(isRead = true) else it },
            unread = (unread - 1).coerceAtLeast(0),
        )
    }

    /** Marks everything read locally. */
    fun withAllRead(): NotificationFeed =
        copy(items = items.map { it.copy(isRead = true) }, unread = 0)
}
