package com.tinhcd.myesalessfa.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bell's own rules: which kinds the filter row offers, and what reading an
 * item does to the badge before the server has answered.
 */
class NotificationTest {

    private fun item(
        kind: NotificationKind,
        id: String,
        read: Boolean = false,
    ) = Notification(
        kind = kind,
        sourceId = id,
        title = "$kind $id",
        body = "",
        isRead = read,
    )

    private fun feed(vararg items: Notification) =
        NotificationFeed(items = items.toList(), unread = items.count { !it.isRead })

    // -------------------------------------------------------------------------
    // Identity
    // -------------------------------------------------------------------------

    /**
     * Two feeds share an id space only by accident, so the key has to carry the
     * kind. Without it a display programme and a POSM programme with the same
     * uuid would collide in the list and in the read markers.
     */
    @Test
    fun `key separates kinds that share an id`() {
        val display = item(NotificationKind.DISPLAY, "same")
        val posm = item(NotificationKind.POSM, "same")

        assertTrue(display.key != posm.key)
        assertEquals("display:same", display.key)
    }

    @Test
    fun `unknown kind falls back rather than throwing`() {
        assertEquals(NotificationKind.PROMOTION, NotificationKind.fromWire("something_new"))
        assertEquals(NotificationKind.WORK_NOTE, NotificationKind.fromWire("work_note"))
    }

    // -------------------------------------------------------------------------
    // Filtering
    // -------------------------------------------------------------------------

    @Test
    fun `null filter is every kind`() {
        val f = feed(
            item(NotificationKind.DISPLAY, "1"),
            item(NotificationKind.POSM, "2"),
        )

        assertEquals(2, f.of(null).size)
        assertEquals(1, f.of(NotificationKind.POSM).size)
    }

    /** A chip for a kind with nothing behind it is a button that does nothing. */
    @Test
    fun `present kinds lists only what is in the feed`() {
        val f = feed(
            item(NotificationKind.DISPLAY, "1"),
            item(NotificationKind.DISPLAY, "2"),
            item(NotificationKind.WORK_NOTE, "3"),
        )

        assertEquals(
            listOf(NotificationKind.DISPLAY, NotificationKind.WORK_NOTE),
            f.presentKinds,
        )
    }

    /** And in enum order, not the order the server happened to send. */
    @Test
    fun `present kinds keeps a stable order`() {
        val f = feed(
            item(NotificationKind.WORK_NOTE, "1"),
            item(NotificationKind.PROMOTION, "2"),
        )

        assertEquals(
            listOf(NotificationKind.PROMOTION, NotificationKind.WORK_NOTE),
            f.presentKinds,
        )
    }

    @Test
    fun `present kinds is empty for an empty feed`() {
        assertTrue(NotificationFeed().presentKinds.isEmpty())
    }

    // -------------------------------------------------------------------------
    // Reading
    // -------------------------------------------------------------------------

    @Test
    fun `reading one item marks it and drops the badge`() {
        val target = item(NotificationKind.DISPLAY, "1")
        val next = feed(target, item(NotificationKind.POSM, "2")).withRead(target.key)

        assertTrue(next.items.first { it.key == target.key }.isRead)
        assertEquals(1, next.unread)
    }

    /** Reading something twice is reading it once — the badge must not double-count. */
    @Test
    fun `reading an already read item changes nothing`() {
        val target = item(NotificationKind.DISPLAY, "1", read = true)
        val f = feed(target, item(NotificationKind.POSM, "2"))

        assertSame(f, f.withRead(target.key))
        assertEquals(1, f.withRead(target.key).unread)
    }

    /** A key from a stale list must not push the badge around. */
    @Test
    fun `reading an unknown key changes nothing`() {
        val f = feed(item(NotificationKind.DISPLAY, "1"))
        assertSame(f, f.withRead("display:gone"))
    }

    /**
     * The badge is the server's number, not a count of the rows on screen, so a
     * filtered list can legitimately hold fewer unread items than the badge says.
     * It must still never go negative.
     */
    @Test
    fun `badge floors at zero when it disagrees with the list`() {
        val target = item(NotificationKind.DISPLAY, "1")
        val f = NotificationFeed(items = listOf(target), unread = 0)

        assertEquals(0, f.withRead(target.key).unread)
    }

    @Test
    fun `mark all clears every row and the badge`() {
        val next = feed(
            item(NotificationKind.DISPLAY, "1"),
            item(NotificationKind.POSM, "2"),
            item(NotificationKind.WORK_NOTE, "3", read = true),
        ).withAllRead()

        assertEquals(0, next.unread)
        assertTrue(next.items.all { it.isRead })
        assertFalse(next.items.isEmpty())
    }

    @Test
    fun `mark all on an empty feed is harmless`() {
        assertEquals(0, NotificationFeed().withAllRead().unread)
    }
}
