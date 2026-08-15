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

    /**
     * A step the server could enable that this build has never heard of.
     *
     * Deliberately fictional. These tests used to name a real-but-unbuilt step, and
     * every time one of those shipped the fixture quietly stopped testing what it
     * claimed to. Every step in the seed is now implemented, so the only honest way to
     * test "cannot render" is with an id no release will ever claim.
     */
    private val UNRENDERABLE = "planogram_check"

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
        // Completions arrive unfiltered, so the assembler does the filtering.
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
                definition(UNRENDERABLE, order = 2, required = true),
            ),
            completions = emptyList(),
            titleOf = titleOf,
        )
        assertTrue(workflow.steps.first().implemented)
        assertFalse(workflow.steps.last().implemented)
    }

    @Test
    fun `a queued completion unblocks check-out before it reaches the server`() {
        // A completion recorded during the visit unblocks check-out: otherwise a rep who finished
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

    // -------------------------------------------------------------------------
    // Prerequisites — "count the shelves before you write the order"
    // -------------------------------------------------------------------------

    private val stockBeforeOrder =
        mapOf(SupportedSteps.TAKE_ORDER to SupportedSteps.STOCK_OUTLET)

    private val bothSteps = listOf(
        definition(SupportedSteps.STOCK_OUTLET, order = 2),
        definition(SupportedSteps.TAKE_ORDER, order = 3),
    )

    @Test
    fun `the order step is closed until the count is done`() {
        val workflow = assembleWorkflow(
            visitId = "v1",
            definition = bothSteps,
            completions = emptyList(),
            titleOf = titleOf,
            prerequisites = stockBeforeOrder,
        )

        val order = workflow.steps.single { it.step.formId == SupportedSteps.TAKE_ORDER }
        assertFalse(order.canOpen)
        assertEquals(SupportedSteps.STOCK_OUTLET, order.waitingOn?.formId)
        // Named, so the screen can say what the rep has to do first.
        assertEquals("Buoc stock_outlet", order.waitingOn?.title)

        // The count itself has no prerequisite and opens immediately.
        assertTrue(workflow.steps.single { it.step.formId == SupportedSteps.STOCK_OUTLET }.canOpen)
    }

    @Test
    fun `completing the count opens the order step`() {
        val workflow = assembleWorkflow(
            visitId = "v1",
            definition = bothSteps,
            completions = listOf(
                StepCompletion("v1", SupportedSteps.STOCK_OUTLET, 1_700_000_000_000),
            ),
            titleOf = titleOf,
            prerequisites = stockBeforeOrder,
        )

        val order = workflow.steps.single { it.step.formId == SupportedSteps.TAKE_ORDER }
        assertTrue(order.canOpen)
        assertNull(order.waitingOn)
    }

    @Test
    fun `a count queued offline opens the order step just as a delivered one does`() {
        // A rep who
        // counted in a dead spot must not then be refused the order.
        val workflow = assembleWorkflow(
            visitId = "v1",
            definition = bothSteps,
            completions = listOf(
                StepCompletion("v1", SupportedSteps.STOCK_OUTLET, 1_700_000_500_000),
            ),
            titleOf = titleOf,
            prerequisites = stockBeforeOrder,
        )
        assertTrue(workflow.steps.single { it.step.formId == SupportedSteps.TAKE_ORDER }.canOpen)
    }

    @Test
    fun `a prerequisite this build cannot render does not close the step behind it`() {
        // Holding the order back for a step the app has no screen for would leave
        // the rep unable to sell, with no way to clear the block.
        val workflow = assembleWorkflow(
            visitId = "v1",
            definition = listOf(
                definition(UNRENDERABLE, order = 1),
                definition(SupportedSteps.TAKE_ORDER, order = 3),
            ),
            completions = emptyList(),
            titleOf = titleOf,
            prerequisites = mapOf(SupportedSteps.TAKE_ORDER to UNRENDERABLE),
        )
        assertTrue(workflow.steps.single { it.step.formId == SupportedSteps.TAKE_ORDER }.canOpen)
    }

    @Test
    fun `a prerequisite the server did not configure is ignored`() {
        // The setting says count first, but this market's workflow has no count
        // step at all. The order step still has to open.
        val workflow = assembleWorkflow(
            visitId = "v1",
            definition = listOf(definition(SupportedSteps.TAKE_ORDER, order = 3)),
            completions = emptyList(),
            titleOf = titleOf,
            prerequisites = stockBeforeOrder,
        )
        assertTrue(workflow.steps.single().canOpen)
    }

    @Test
    fun `with no prerequisites configured every implemented step opens`() {
        val workflow = assembleWorkflow(
            visitId = "v1",
            definition = bothSteps,
            completions = emptyList(),
            titleOf = titleOf,
        )
        assertTrue(workflow.steps.all { it.canOpen })
        assertTrue(workflow.steps.all { it.waitingOn == null })
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
