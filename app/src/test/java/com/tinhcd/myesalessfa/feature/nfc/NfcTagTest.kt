package com.tinhcd.myesalessfa.feature.nfc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NfcTagTest {

    @Test
    fun `a UID prints as upper-case hex, first byte first, with leading zeros`() {
        val id = byteArrayOf(0x04, 0xA1.toByte(), 0x0B, 0xFF.toByte(), 0x00, 0x7E, 0x80.toByte())
        assertEquals("04A10BFF007E80", id.toTagUid())
    }

    @Test
    fun `a route tap is handed over once, to its own customer, while fresh`() {
        val tap = RecentNfcTap()

        tap.record("c1", "04A1", nowMs = 1_000)
        assertNull("another customer's check-in does not get it", tap.take("c2", nowMs = 2_000))
        assertNull("and taking it, even wrongly, clears it", tap.take("c1", nowMs = 2_000))

        tap.record("c1", "04A1", nowMs = 1_000)
        assertEquals("04A1", tap.take("c1", nowMs = 30_000))
        assertNull(tap.take("c1", nowMs = 30_001))

        tap.record("c1", "04A1", nowMs = 1_000)
        assertNull("a minute on, it is stale", tap.take("c1", nowMs = 61_000))
    }
}
