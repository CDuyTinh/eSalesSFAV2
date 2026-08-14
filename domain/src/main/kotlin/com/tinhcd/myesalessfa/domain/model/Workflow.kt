package com.tinhcd.myesalessfa.domain.model

/**
 * One configured step of the in-call workflow, as head office defined it.
 *
 * The client does not know the list ahead of time — it renders whatever
 * `sales_step` returns. Adding a step to a market is a data change, not a
 * release, which is the single most valuable property of the legacy design and
 * the reason it is preserved here.
 */
data class SalesStep(
    val formId: String,
    val order: Int,
    val titleKey: String,
    val isRequired: Boolean,
    /** Flattened from the server's jsonb, e.g. {"photo_min": "1"}. */
    val config: Map<String, String> = emptyMap(),
) {
    /**
     * Config arrives as strings whatever the jsonb type was, and head office can
     * type anything into it. A missing or unparseable value falls back to
     * [default] rather than stranding a rep mid-visit over a typo in a setting.
     */
    fun configInt(key: String, default: Int = 0): Int =
        config[key]?.trim()?.toIntOrNull() ?: default

    fun configBoolean(key: String, default: Boolean = false): Boolean =
        when (config[key]?.trim()?.lowercase()) {
            "true", "1", "yes" -> true
            "false", "0", "no" -> false
            else -> default
        }
}

/** Config keys this build understands. Unknown keys are simply ignored. */
object StepConfig {
    /** Minimum characters a free-text step will accept. */
    const val NOTE_MIN_LENGTH = "note_min_length"
}

/**
 * Steps this build of the app can actually render.
 *
 * The server may enable steps a released app has never heard of — that is the
 * price of a data-driven workflow. Naming them here in one place keeps the
 * "can we show this?" question out of both the data layer and the UI, and lets
 * the check-out rule avoid blocking on a screen that does not exist.
 */
object SupportedSteps {
    const val OUTSIDE_CHECKING = "outside_checking"
    const val TAKE_ORDER = "take_order"
    const val FEEDBACK = "feedback"

    val formIds: Set<String> = setOf(OUTSIDE_CHECKING, TAKE_ORDER, FEEDBACK)
}

data class WorkflowStep(
    val step: SalesStep,
    /** Resolved through the translation table; falls back to the raw key. */
    val title: String,
    val completedAtEpochMs: Long?,
    /** False for steps that exist in config but have no screen yet. */
    val implemented: Boolean,
) {
    val isDone: Boolean get() = completedAtEpochMs != null
}

data class VisitWorkflow(
    val visitId: String,
    val steps: List<WorkflowStep>,
) {
    /**
     * Required steps still outstanding. Steps with no screen yet are excluded:
     * blocking a rep on something the app cannot even show them would strand
     * them mid-visit with no way out.
     */
    val blockingSteps: List<WorkflowStep>
        get() = steps.filter { it.step.isRequired && it.implemented && !it.isDone }

    val canCheckOut: Boolean get() = blockingSteps.isEmpty()

    val doneCount: Int get() = steps.count { it.isDone }
}

/**
 * A step recorded as done — either read back from the server or still sitting in
 * the outbox waiting for signal. Both are the same fact as far as the rep is
 * concerned, so they are the same type here.
 */
data class StepCompletion(
    val visitId: String,
    val formId: String,
    val atEpochMs: Long,
)

/**
 * Builds the model the in-call screen renders from the configured step list and
 * everything known about what the rep has finished.
 *
 * This lives in the domain rather than the data layer because the interesting
 * parts are rules, not plumbing: completions for other visits must not leak in,
 * a step redone offline counts at its latest time rather than its first, and the
 * server's order field decides the sequence — not whatever order rows arrived
 * in.
 */
fun assembleWorkflow(
    visitId: String,
    definition: List<SalesStep>,
    completions: List<StepCompletion>,
    titleOf: (SalesStep) -> String,
): VisitWorkflow {
    // Latest wins. A locally queued completion is by construction newer than
    // the server's copy, which is exactly the case that matters: a step redone
    // in a dead spot must not appear undone because the old row came back.
    val completedAt: Map<String, Long> = completions
        .filter { it.visitId == visitId }
        .groupBy { it.formId }
        .mapValues { (_, records) -> records.maxOf { it.atEpochMs } }

    return VisitWorkflow(
        visitId = visitId,
        steps = definition
            .sortedBy { it.order }
            .map { step ->
                WorkflowStep(
                    step = step,
                    title = titleOf(step),
                    completedAtEpochMs = completedAt[step.formId],
                    implemented = step.formId in SupportedSteps.formIds,
                )
            },
    )
}
