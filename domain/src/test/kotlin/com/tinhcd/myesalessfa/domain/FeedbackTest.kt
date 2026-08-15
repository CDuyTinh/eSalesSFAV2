package com.tinhcd.myesalessfa.domain

import com.tinhcd.myesalessfa.domain.model.DraftFeedback
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedbackTest {

    private fun draft(
        note: String = "",
        topicId: String? = null,
        audioPath: String? = null,
        audioSeconds: Int = 0,
        noteMinLength: Int = 5,
        allowAudio: Boolean = true,
    ) = DraftFeedback(
        visitId = "v1",
        topicId = topicId,
        note = note,
        audioPath = audioPath,
        audioSeconds = audioSeconds,
        noteMinLength = noteMinLength,
        allowAudio = allowAudio,
    )

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
    fun `a long enough note is submittable with no topic and no audio`() {
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
        val d = draft(note = "ok", audioPath = "/tmp/a.m4a", audioSeconds = 30)
        assertTrue(d.hasAudio)
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
}
