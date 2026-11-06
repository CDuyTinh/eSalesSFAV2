package com.tinhcd.myesalessfa.core.permission

import org.junit.Assert.assertEquals
import org.junit.Test

class PermissionOutcomeTest {

    private val fine = "android.permission.ACCESS_FINE_LOCATION"
    private val coarse = "android.permission.ACCESS_COARSE_LOCATION"
    private val camera = "android.permission.CAMERA"

    private fun outcome(
        results: Map<String, Boolean>,
        anyOf: Boolean = false,
        explain: Set<String> = emptySet(),
        asked: Set<String> = emptySet(),
    ) = permissionOutcome(results, anyOf, { it in explain }, { it in asked })

    @Test
    fun `granted when held`() {
        assertEquals(PermissionOutcome.GRANTED, outcome(mapOf(camera to true)))
    }

    @Test
    fun `approximate location is enough when any of the set will do`() {
        assertEquals(PermissionOutcome.GRANTED, outcome(mapOf(fine to false, coarse to true), anyOf = true))
        assertEquals(PermissionOutcome.DENIED, outcome(mapOf(fine to false, coarse to true), explain = setOf(fine)))
    }

    @Test
    fun `refused but explainable is denied, so the app can ask again`() {
        assertEquals(PermissionOutcome.DENIED, outcome(mapOf(camera to false), explain = setOf(camera), asked = setOf(camera)))
    }

    @Test
    fun `a first dismissal is denied, not blocked`() {
        // No rationale wanted, but the app never asked before: the prompt was dismissed, not refused for good.
        assertEquals(PermissionOutcome.DENIED, outcome(mapOf(camera to false)))
    }

    @Test
    fun `refused with no rationale after asking before is blocked`() {
        assertEquals(PermissionOutcome.BLOCKED, outcome(mapOf(camera to false), asked = setOf(camera)))
    }

    @Test
    fun `blocked only when every refused permission is`() {
        val results = mapOf(fine to false, coarse to false)
        assertEquals(PermissionOutcome.DENIED, outcome(results, asked = setOf(fine)))
        assertEquals(PermissionOutcome.BLOCKED, outcome(results, asked = setOf(fine, coarse)))
    }
}
