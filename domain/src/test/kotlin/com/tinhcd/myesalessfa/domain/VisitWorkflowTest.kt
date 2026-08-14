package com.tinhcd.myesalessfa.domain

import com.tinhcd.myesalessfa.domain.model.SalesStep
import com.tinhcd.myesalessfa.domain.model.VisitWorkflow
import com.tinhcd.myesalessfa.domain.model.WorkflowStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VisitWorkflowTest {

    private fun step(
        formId: String,
        required: Boolean,
        done: Boolean,
        implemented: Boolean = true,
    ) = WorkflowStep(
        step = SalesStep(
            formId = formId,
            order = 1,
            titleKey = "step_$formId",
            isRequired = required,
        ),
        title = formId,
        completedAtEpochMs = if (done) 1_700_000_000_000 else null,
        implemented = implemented,
    )

    @Test
    fun `check-out is blocked while a required step is outstanding`() {
        val workflow = VisitWorkflow(
            visitId = "v1",
            steps = listOf(
                step("outside_checking", required = true, done = false),
                step("take_order", required = false, done = false),
            ),
        )
        assertFalse(workflow.canCheckOut)
        assertEquals(listOf("outside_checking"), workflow.blockingSteps.map { it.step.formId })
    }

    @Test
    fun `optional steps never block check-out`() {
        val workflow = VisitWorkflow(
            visitId = "v1",
            steps = listOf(
                step("outside_checking", required = true, done = true),
                step("take_order", required = false, done = false),
                step("market_info", required = false, done = false),
            ),
        )
        assertTrue(workflow.canCheckOut)
    }

    @Test
    fun `a required step with no screen yet does not strand the rep`() {
        // Head office enabled a step this build cannot render. Blocking on it
        // would leave the rep unable to close the visit at all.
        val workflow = VisitWorkflow(
            visitId = "v1",
            steps = listOf(
                step("display_remark", required = true, done = false, implemented = false),
            ),
        )
        assertTrue(workflow.canCheckOut)
        assertTrue(workflow.blockingSteps.isEmpty())
    }

    @Test
    fun `done count reflects completed steps regardless of whether they were required`() {
        val workflow = VisitWorkflow(
            visitId = "v1",
            steps = listOf(
                step("a", required = true, done = true),
                step("b", required = false, done = true),
                step("c", required = false, done = false),
            ),
        )
        assertEquals(2, workflow.doneCount)
    }
}
