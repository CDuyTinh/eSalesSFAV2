package com.tinhcd.myesalessfa.domain

import com.tinhcd.myesalessfa.domain.model.AuditPhoto
import com.tinhcd.myesalessfa.domain.model.DisplayProgram
import com.tinhcd.myesalessfa.domain.model.DraftDisplayAudit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Scoring a display programme, not just photographing a shelf.
 *
 * The rule under test is the one the server also enforces: a programme audit is
 * only submittable once the rep has both counted and judged. Losing either half
 * would leave a row saying a display was inspected and nothing about whether it
 * passed, which is the one thing the programme exists to record.
 */
class DisplayProgramScoringTest {

    private val program = DisplayProgram(
        programId = "p1",
        programCode = "TB2609",
        programName = "Trưng bày nước giải khát quý 3",
        specification = null,
        levelId = "l2",
        levelName = "Mức 2 - 12 mặt",
        requiredFaces = 12,
        bonusAmount = 700_000,
        registered = true,
        registrationStatus = "approved",
    )

    private fun draft(program: DisplayProgram? = null) = DraftDisplayAudit(
        visitId = "v1",
        customerId = "c1",
        photos = listOf(AuditPhoto(localPath = "/p/1.jpg", takenAtEpochMs = 0)),
        program = program,
    )

    @Test
    fun `a plain audit needs only its photos`() {
        assertTrue(draft().canSubmit)
    }

    @Test
    fun `a programme audit needs the count and the verdict as well`() {
        val scoring = draft(program)

        assertFalse("neither answered", scoring.canSubmit)
        assertFalse("counted but not judged", scoring.copy(countedFaces = 12).canSubmit)
        assertFalse("judged but not counted", scoring.copy(achieved = true).canSubmit)
        assertTrue(scoring.copy(countedFaces = 12, achieved = true).canSubmit)
    }

    @Test
    fun `zero facings is an answer, not a missing one`() {
        // An empty shelf is exactly what this step is meant to catch, so counting
        // nothing must still be submittable — with a verdict of not achieved.
        val empty = draft(program).copy(countedFaces = 0, achieved = false)

        assertTrue(empty.canSubmit)
    }

    @Test
    fun `photos are still required of a programme audit`() {
        val noPhotos = draft(program).copy(
            photos = emptyList(),
            countedFaces = 12,
            achieved = true,
        )

        assertFalse(noPhotos.canSubmit)
    }

    @Test
    fun `shortfall counts down to the registered level and stops at zero`() {
        assertEquals(12, program.shortfall(0))
        assertEquals(2, program.shortfall(10))
        assertEquals(0, program.shortfall(12))
        assertEquals(0, program.shortfall(20))
    }

    @Test
    fun `a display can be judged achieved even when the count falls short`() {
        // The verdict is the rep's, not the arithmetic's: a display can miss the
        // facing target and still be built to specification, which is why the
        // legacy asks for both and derives neither from the other.
        val generous = draft(program).copy(countedFaces = 10, achieved = true)

        assertTrue(generous.canSubmit)
        assertEquals(2, program.shortfall(generous.countedFaces!!))
    }

    @Test
    fun `a programme is scored only once it carries a verdict`() {
        assertFalse(program.isScored)
        assertFalse(program.copy(countedFaces = 12).isScored)
        assertTrue(program.copy(countedFaces = 12, achieved = false).isScored)
    }

    @Test
    fun `a registration awaiting head office is still audited`() {
        val waiting = program.copy(registrationStatus = "pending")

        assertTrue(waiting.isPending)
        assertFalse(program.isPending)
        // Nothing about pending blocks the score: the outlet built the display and
        // refusing to record it is how a rep gets blamed for the paperwork.
        assertTrue(draft(waiting).copy(countedFaces = 12, achieved = true).canSubmit)
    }
}
