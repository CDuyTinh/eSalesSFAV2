package com.tinhcd.myesalessfa.domain

import com.tinhcd.myesalessfa.domain.model.SalesStep
import com.tinhcd.myesalessfa.domain.model.StepCompletion
import com.tinhcd.myesalessfa.domain.model.SupportedSteps
import com.tinhcd.myesalessfa.domain.model.assembleWorkflow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AssembleWorkflowTest {

    private fun definition(formId: String, order: Int, required: Boolean = false) = SalesStep(
        formId = formId,
        order = order,
        titleKey = "step_$formId",
        isRequired = required,
    )

    private val titleOf: (SalesStep) -> String = { "Buoc ${it.formId}" }

    @Test
    fun `steps follow the server's order, not the order rows arrived in`() {
        val workflow = assembleWorkflow(
            visitId = "v1",
            definition = listOf(
                definition("feedback", order = 7),
                definition(SupportedSteps.OUTSIDE_CHECKING, order = 1),
                definition("take_order", order = 3),
            ),
            completions = emptyList(),
            titleOf = titleOf,
        )
        assertEquals(
            listOf(SupportedSteps.OUTSIDE_CHECKING, "take_order", "feedback"),
            workflow.steps.map { it.step.formId },
        )
    }

    @Test
    fun `a completion from another visit does not mark this visit's step done`() {
        // The outbox is shared across visits, so its entries arrive unfiltered.
        val workflow = assembleWorkflow(
            visitId = "v1",
            definition = listOf(definition(SupportedSteps.OUTSIDE_CHECKING, order = 1)),
            completions = listOf(
                StepCompletion("v2", SupportedSteps.OUTSIDE_CHECKING, 1_700_000_000_000),
            ),
            titleOf = titleOf,
        )
        assertNull(workflow.steps.single().completedAtEpochMs)
        assertEquals(0, workflow.doneCount)
    }

    @Test
    fun `redoing a step offline keeps the newer time, whichever order it arrives in`() {
        // The stale server row and the fresh queued one both show up. Taking the
        // server's would make the rep's redo look like it never happened.
        val stale = StepCompletion("v1", SupportedSteps.FEEDBACK, 1_700_000_000_000)
        val fresh = StepCompletion("v1", SupportedSteps.FEEDBACK, 1_700_000_900_000)

        val fromServerFirst = assembleWorkflow(
            visitId = "v1",
            definition = listOf(definition(SupportedSteps.FEEDBACK, order = 1)),
            completions = listOf(stale, fresh),
            titleOf = titleOf,
        )
        val fromQueueFirst = assembleWorkflow(
            visitId = "v1",
            definition = listOf(definition(SupportedSteps.FEEDBACK, order = 1)),
            completions = listOf(fresh, stale),
            titleOf = titleOf,
        )

        assertEquals(fresh.atEpochMs, fromServerFirst.steps.single().completedAtEpochMs)
        assertEquals(fresh.atEpochMs, fromQueueFirst.steps.single().completedAtEpochMs)
    }

    @Test
    fun `a step this build cannot render is marked unimplemented`() {
        val workflow = assembleWorkflow(
            visitId = "v1",
            definition = listOf(
                definition(SupportedSteps.OUTSIDE_CHECKING, order = 1),
                definition("posm_status", order = 2, required = true),
            ),
            completions = emptyList(),
            titleOf = titleOf,
        )
        assertTrue(workflow.steps.first().implemented)
        assertFalse(workflow.steps.last().implemented)
    }

    @Test
    fun `a queued completion unblocks check-out before it reaches the server`() {
        // The whole point of merging the outbox in: otherwise a rep who finished
        // the mandatory step in a dead spot cannot close the visit.
        val workflow = assembleWorkflow(
            visitId = "v1",
            definition = listOf(definition(SupportedSteps.OUTSIDE_CHECKING, order = 1, required = true)),
            completions = listOf(
                StepCompletion("v1", SupportedSteps.OUTSIDE_CHECKING, 1_700_000_000_000),
            ),
            titleOf = titleOf,
        )
        assertTrue(workflow.canCheckOut)
    }

    @Test
    fun `titles are resolved through the supplied lookup`() {
        val workflow = assembleWorkflow(
            visitId = "v1",
            definition = listOf(definition(SupportedSteps.FEEDBACK, order = 1)),
            completions = emptyList(),
            titleOf = titleOf,
        )
        assertEquals("Buoc feedback", workflow.steps.single().title)
    }
}
