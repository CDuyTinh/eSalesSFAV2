package com.tinhcd.myesalessfa.domain

import com.tinhcd.myesalessfa.domain.model.AuditPhoto
import com.tinhcd.myesalessfa.domain.model.DraftDisplayAudit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplayAuditPhotoLimitTest {

    private fun photo(name: String) = AuditPhoto(
        localPath = "/data/photos/$name.jpg",
        takenAtEpochMs = 0,
        sizeBytes = 100_000,
    )

    private fun draft(min: Int = 1, max: Int = 6) =
        DraftDisplayAudit(visitId = "v1", customerId = "c1", photoMin = min, photoMax = max)

    @Test
    fun `an empty audit can take a photo and cannot be submitted`() {
        val audit = draft()

        assertTrue(audit.canAddPhoto)
        assertFalse(audit.canSubmit)
        assertEquals(1, audit.photosStillNeeded)
    }

    @Test
    fun `reaching the minimum makes it submittable`() {
        val audit = draft(min = 2).withPhoto(photo("a")).withPhoto(photo("b"))

        assertTrue(audit.canSubmit)
        assertEquals(0, audit.photosStillNeeded)
        // Still under the ceiling, so more are allowed.
        assertTrue(audit.canAddPhoto)
    }

    @Test
    fun `the ceiling closes the camera`() {
        val full = (1..3).fold(draft(max = 3)) { audit, n -> audit.withPhoto(photo("p$n")) }

        assertEquals(3, full.photoCount)
        assertFalse(full.canAddPhoto)
    }

    @Test
    fun `a photo past the ceiling is ignored rather than accepted`() {
        // The screen hides the button, but the camera result can still arrive —
        // a shot taken just as the third one landed. Dropping it here means no
        // path can put a seventh photo in an audit configured for six.
        val full = (1..3).fold(draft(max = 3)) { audit, n -> audit.withPhoto(photo("p$n")) }
        val overflowed = full.withPhoto(photo("p4"))

        assertEquals(3, overflowed.photoCount)
        assertEquals(full.photos, overflowed.photos)
    }

    @Test
    fun `removing one opens the camera again`() {
        val full = (1..3).fold(draft(max = 3)) { audit, n -> audit.withPhoto(photo("p$n")) }
        val after = full.withoutPhoto("/data/photos/p2.jpg")

        assertEquals(2, after.photoCount)
        assertTrue(after.canAddPhoto)
        // ...and the one removed is the one that went.
        assertEquals(listOf("p1", "p3"), after.photos.map { it.localPath.substringAfterLast('/').removeSuffix(".jpg") })
    }

    @Test
    fun `the upload size is the sum of what will be sent`() {
        val audit = draft().withPhoto(photo("a")).withPhoto(photo("b"))

        assertEquals(200_000L, audit.totalSizeBytes)
    }
}
