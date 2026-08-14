package com.tinhcd.myesalessfa.domain

import com.tinhcd.myesalessfa.domain.model.SalesStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Step config is typed by head office into a jsonb column and reaches the client
 * as strings. Nothing validates it on the way, so reading it must never be able
 * to take a rep's visit down.
 */
class SalesStepConfigTest {

    private fun step(config: Map<String, String>) = SalesStep(
        formId = "feedback",
        order = 1,
        titleKey = "step_feedback",
        isRequired = false,
        config = config,
    )

    @Test
    fun `numbers are read, whitespace and all`() {
        assertEquals(10, step(mapOf("note_min_length" to "10")).configInt("note_min_length"))
        assertEquals(10, step(mapOf("note_min_length" to " 10 ")).configInt("note_min_length"))
    }

    @Test
    fun `a missing or unparseable number falls back instead of throwing`() {
        assertEquals(3, step(emptyMap()).configInt("note_min_length", default = 3))
        assertEquals(3, step(mapOf("note_min_length" to "ten")).configInt("note_min_length", default = 3))
        assertEquals(3, step(mapOf("note_min_length" to "")).configInt("note_min_length", default = 3))
    }

    @Test
    fun `booleans accept the spellings a jsonb column actually produces`() {
        assertTrue(step(mapOf("allow_audio" to "true")).configBoolean("allow_audio"))
        assertTrue(step(mapOf("allow_audio" to "TRUE")).configBoolean("allow_audio"))
        assertTrue(step(mapOf("allow_audio" to "1")).configBoolean("allow_audio"))
        assertFalse(step(mapOf("allow_audio" to "false")).configBoolean("allow_audio"))
        assertFalse(step(mapOf("allow_audio" to "0")).configBoolean("allow_audio"))
    }

    @Test
    fun `an unrecognised boolean keeps the default rather than reading as false`() {
        // Defaulting a permissive flag to false on a typo would silently disable
        // a capability head office believes it turned on.
        assertTrue(step(mapOf("allow_audio" to "yep")).configBoolean("allow_audio", default = true))
        assertFalse(step(emptyMap()).configBoolean("allow_audio"))
    }
}
