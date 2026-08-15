package com.tinhcd.myesalessfa.domain

import com.tinhcd.myesalessfa.domain.model.AuditPhoto
import com.tinhcd.myesalessfa.domain.model.DraftDisplayAudit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `submit_display_audit` enforces the same minimum server-side, reading it from the
 * same `sales_step.config`. Verified against the live function: an audit with no
 * photos was refused with "0 photos supplied, 1 required". These tests are the
 * client half of that agreement — the rep should be stopped while still in front of
 * the shelf, not after the upload has failed somewhere else.
 */
class DisplayAuditTest {

    private fun photo(path: String, size: Long = 240_000) =
        AuditPhoto(localPath = path, takenAtEpochMs = 1_700_000_000_000, sizeBytes = size)

    private fun draft(photoMin: Int = 1) =
        DraftDisplayAudit(visitId = "v1", customerId = "c1", photoMin = photoMin)

    @Test
    fun `an audit with no photo cannot be submitted`() {
        val empty = draft()
        assertFalse(empty.canSubmit)
        assertEquals(1, empty.photosStillNeeded)
    }

    @Test
    fun `one photo satisfies the default minimum`() {
        val audit = draft().withPhoto(photo("/a.jpg"))
        assertTrue(audit.canSubmit)
        assertEquals(0, audit.photosStillNeeded)
    }

    @Test
    fun `a market configured for three photos says how many are still missing`() {
        // The count is what the screen shows, so it has to be the number of photos
        // left rather than a bare "not enough".
        var audit = draft(photoMin = 3)
        assertEquals(3, audit.photosStillNeeded)

        audit = audit.withPhoto(photo("/a.jpg"))
        assertEquals(2, audit.photosStillNeeded)
        assertFalse(audit.canSubmit)

        audit = audit.withPhoto(photo("/b.jpg")).withPhoto(photo("/c.jpg"))
        assertEquals(0, audit.photosStillNeeded)
        assertTrue(audit.canSubmit)
    }

    @Test
    fun `extra photos beyond the minimum are kept, not rejected`() {
        // A rep photographing three angles of one display is doing the job well.
        val audit = draft(photoMin = 1)
            .withPhoto(photo("/a.jpg"))
            .withPhoto(photo("/b.jpg"))
            .withPhoto(photo("/c.jpg"))

        assertEquals(3, audit.photoCount)
        assertTrue(audit.canSubmit)
        assertEquals(0, audit.photosStillNeeded)
    }

    @Test
    fun `removing a photo can put the audit back below the minimum`() {
        // Deleting a bad shot must re-block submission rather than leaving a stale
        // "ready" state the server would then refuse.
        val audit = draft(photoMin = 2)
            .withPhoto(photo("/a.jpg"))
            .withPhoto(photo("/b.jpg"))
        assertTrue(audit.canSubmit)

        val reduced = audit.withoutPhoto("/a.jpg")
        assertFalse(reduced.canSubmit)
        assertEquals(1, reduced.photosStillNeeded)
        assertEquals(listOf("/b.jpg"), reduced.photos.map { it.localPath })
    }

    @Test
    fun `removing a path that is not there changes nothing`() {
        val audit = draft().withPhoto(photo("/a.jpg"))
        assertEquals(audit, audit.withoutPhoto("/nope.jpg"))
    }

    @Test
    fun `a zero minimum still permits an audit with no photo`() {
        // Head office can turn the requirement off. The client honours the config
        // rather than second-guessing it — the server reads the same key.
        assertTrue(draft(photoMin = 0).canSubmit)
        assertEquals(0, draft(photoMin = 0).photosStillNeeded)
    }

    @Test
    fun `the queued size is the sum of what has to be uploaded`() {
        // Shown to the rep, because on a slow connection the number of kilobytes is
        // the honest answer to "why is this taking so long".
        val audit = draft()
            .withPhoto(photo("/a.jpg", size = 240_000))
            .withPhoto(photo("/b.jpg", size = 310_000))

        assertEquals(550_000L, audit.totalSizeBytes)
    }
}
