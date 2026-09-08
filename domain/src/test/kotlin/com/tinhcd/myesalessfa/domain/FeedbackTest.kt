package com.tinhcd.myesalessfa.domain

import com.tinhcd.myesalessfa.domain.model.DraftFeedback
import com.tinhcd.myesalessfa.domain.model.FeedbackPhoto
import com.tinhcd.myesalessfa.domain.model.FeedbackRecording
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedbackTest {

    private fun draft(
        note: String = "",
        topicId: String? = null,
        photos: List<FeedbackPhoto> = emptyList(),
        recordings: List<FeedbackRecording> = emptyList(),
        noteMinLength: Int = 5,
        photoMin: Int = 0,
        photoMax: Int = 5,
        audioMaxSeconds: Int = 300,
        audioTotalSeconds: Int = 900,
        allowAudio: Boolean = true,
    ) = DraftFeedback(
        visitId = "v1",
        topicId = topicId,
        note = note,
        photos = photos,
        recordings = recordings,
        noteMinLength = noteMinLength,
        photoMin = photoMin,
        photoMax = photoMax,
        audioMaxSeconds = audioMaxSeconds,
        audioTotalSeconds = audioTotalSeconds,
        allowAudio = allowAudio,
    )

    private fun photo(path: String) = FeedbackPhoto(path, takenAtEpochMs = 1L, sizeBytes = 100)

    private fun clip(path: String, seconds: Int) =
        FeedbackRecording(path, seconds = seconds, recordedAtEpochMs = 1L, sizeBytes = 50)

    @Test
    fun `the countdown reports how much more the note needs`() {
        assertEquals(5, draft(note = "").charsStillNeeded)
        assertEquals(2, draft(note = "abc").charsStillNeeded)
        assertEquals(0, draft(note = "abcde").charsStillNeeded)
        assertEquals(0, draft(note = "abcdefgh").charsStillNeeded)
    }

    @Test
    fun `whitespace does not count towards the minimum`() {
        // Otherwise five spaces satisfies a step that exists to record something, and
        // the server would reject it after the rep thought they were done — it trims
        // before measuring too.
        assertEquals(5, draft(note = "      ").charsStillNeeded)
        assertFalse(draft(note = "      ").canSubmit)
    }

    @Test
    fun `a long enough note is submittable with no topic and no media`() {
        // The topic is optional by design: a market that has configured none still
        // needs the step to work.
        val d = draft(note = "khach khen hang moi")
        assertTrue(d.canSubmit)
        assertEquals(null, d.topicId)
        assertFalse(d.hasAudio)
    }

    @Test
    fun `a recording does not substitute for the note`() {
        // Nobody at head office can search or route a sound file. The audio is
        // evidence attached to a written summary, not a replacement for it.
        val d = draft(note = "ok", recordings = listOf(clip("/a.m4a", 30)))
        assertTrue(d.hasAudio)
        assertFalse(d.canSubmit)
    }

    @Test
    fun `a photo does not substitute for the note either`() {
        // The legacy accepts a submission with no words at all — its content check is
        // commented out — which leaves head office a picture and no idea why.
        val d = draft(note = "", photos = listOf(photo("/a.jpg")))
        assertFalse(d.canSubmit)
    }

    @Test
    fun `a note at exactly the minimum is accepted`() {
        assertTrue(draft(note = "12345").canSubmit)
    }

    @Test
    fun `no configured minimum still accepts an empty note`() {
        // An optional step with no floor is allowed to record nothing. The required
        // case is raised to one character by the caller, not by this type.
        assertTrue(draft(note = "", noteMinLength = 0).canSubmit)
    }

    // -------------------------------------------------------------------------
    // Photos — FEEDBACK_CUSTOMER_IMAGE_REQUIRED and FEEDBACK_CUSTOMER_IMAGE
    // -------------------------------------------------------------------------

    @Test
    fun `no floor means the step is finishable with nothing photographed`() {
        // Plenty of feedback is about a price or a delivery, with nothing to point a
        // camera at.
        val d = draft(note = "khach hoi gia")
        assertEquals(0, d.photosStillNeeded)
        assertTrue(d.canSubmit)
    }

    @Test
    fun `a configured floor holds the step until it is met`() {
        val d = draft(note = "thung bi mop", photoMin = 2)
        assertEquals(2, d.photosStillNeeded)
        assertFalse(d.canSubmit)

        val one = d.withPhoto(photo("/a.jpg"))
        assertEquals(1, one.photosStillNeeded)
        assertFalse(one.canSubmit)

        val two = one.withPhoto(photo("/b.jpg"))
        assertEquals(0, two.photosStillNeeded)
        assertTrue(two.canSubmit)
    }

    @Test
    fun `the ceiling is a ceiling`() {
        var d = draft(note = "nhieu anh qua", photoMax = 2)
        repeat(5) { d = d.withPhoto(photo("/$it.jpg")) }

        assertEquals(2, d.photos.size)
        assertFalse(d.canAddPhoto)
    }

    @Test
    fun `a photo can be taken back`() {
        val d = draft(note = "bo bot anh")
            .withPhoto(photo("/a.jpg"))
            .withPhoto(photo("/b.jpg"))
            .withoutPhoto("/a.jpg")

        assertEquals(listOf("/b.jpg"), d.photos.map { it.localPath })
    }

    // -------------------------------------------------------------------------
    // Recordings — SALES_RECORD_TIME_FILE and SALES_RECORD_MAX_REALTIME
    // -------------------------------------------------------------------------

    @Test
    fun `several clips add up to the total`() {
        val d = draft(note = "khach noi dai")
            .withRecording(clip("/1.m4a", 120))
            .withRecording(clip("/2.m4a", 45))

        assertEquals(2, d.recordings.size)
        assertEquals(165, d.totalAudioSeconds)
        assertEquals(900 - 165, d.audioSecondsLeft)
    }

    @Test
    fun `the next clip is capped by whichever limit bites first`() {
        // Per-clip while there is plenty of budget left.
        val fresh = draft(note = "moi bat dau", audioMaxSeconds = 300, audioTotalSeconds = 900)
        assertEquals(300, fresh.nextClipSeconds)

        // The remaining total once it is smaller than one clip's worth.
        val nearlySpent = fresh.withRecording(clip("/1.m4a", 300))
            .withRecording(clip("/2.m4a", 300))
            .withRecording(clip("/3.m4a", 250))
        assertEquals(50, nearlySpent.nextClipSeconds)
        assertTrue(nearlySpent.canRecord)
    }

    @Test
    fun `recording stops being offered once the budget is spent`() {
        val d = draft(note = "het gio", audioMaxSeconds = 60, audioTotalSeconds = 120)
            .withRecording(clip("/1.m4a", 60))
            .withRecording(clip("/2.m4a", 60))

        assertEquals(0, d.audioSecondsLeft)
        assertEquals(0, d.nextClipSeconds)
        assertFalse(d.canRecord)
        // Still submittable: the audio was never the requirement.
        assertTrue(d.canSubmit)
    }

    @Test
    fun `a clip longer than what is left is clipped rather than refused`() {
        // A recorder that overran must not be able to book more than the step allows,
        // and throwing the clip away would lose what the customer said.
        val d = draft(note = "ghi qua dai", audioMaxSeconds = 60, audioTotalSeconds = 100)
            .withRecording(clip("/1.m4a", 60))
            .withRecording(clip("/2.m4a", 60))

        assertEquals(2, d.recordings.size)
        assertEquals(100, d.totalAudioSeconds)
        assertEquals(40, d.recordings.last().seconds)
    }

    @Test
    fun `a clip with nothing in it is not kept`() {
        val d = draft(note = "khong co gi").withRecording(clip("/1.m4a", 0))
        assertTrue(d.recordings.isEmpty())
    }

    @Test
    fun `recording is off entirely where the step does not allow it`() {
        assertFalse(draft(note = "khong cho ghi am", allowAudio = false).canRecord)
    }

    @Test
    fun `a clip can be taken back`() {
        val d = draft(note = "bo mot doan")
            .withRecording(clip("/1.m4a", 30))
            .withRecording(clip("/2.m4a", 30))
            .withoutRecording("/1.m4a")

        assertEquals(listOf("/2.m4a"), d.recordings.map { it.localPath })
        assertEquals(30, d.totalAudioSeconds)
    }

    @Test
    fun `bytes queued counts every file waiting to upload`() {
        val d = draft(note = "co ca anh lan tieng")
            .withPhoto(photo("/a.jpg"))
            .withPhoto(photo("/b.jpg"))
            .withRecording(clip("/1.m4a", 10))

        assertEquals(250, d.totalSizeBytes)
    }
}
