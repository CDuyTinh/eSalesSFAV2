package com.tinhcd.myesalessfa.domain.model

import java.time.LocalDate

/**
 * One outlet on today's route, with whatever the rep has planned for it and what
 * they last sold there.
 *
 * [lastAmount] is the rep's own last order at this outlet, not the outlet's
 * history: `sales_order` is scoped to the rep, so that is the honest extent of
 * it, and the screen says so rather than implying a fuller picture.
 */
data class DailyTargetStop(
    val customerId: String,
    val customerCode: String,
    val customerName: String,
    val address: String?,
    val visitOrder: Int,
    val target: Long,
    /** False when nothing has been planned for this outlet yet. */
    val hasTarget: Boolean,
    val lastAmount: Long?,
    val lastDate: LocalDate?,
)

/**
 * The day's plan being filled in.
 *
 * Held as a map of edits over the loaded stops rather than a mutated copy of
 * them, so what the rep changed is always distinguishable from what was already
 * saved — which is what lets the screen offer to save only when something
 * actually differs.
 */
data class DailyTargetPlan(
    val date: LocalDate,
    val stops: List<DailyTargetStop> = emptyList(),
    val edits: Map<String, Long> = emptyMap(),
) {
    /** What the field shows: the edit if there is one, else what was saved. */
    fun amountFor(stop: DailyTargetStop): Long = edits[stop.customerId] ?: stop.target

    val total: Long get() = stops.sumOf { amountFor(it) }

    /** How many outlets the day's plan actually covers. Zero is not a plan. */
    val plannedCount: Int get() = stops.count { amountFor(it) > 0 }

    /**
     * Only what changed, and only when it changed.
     *
     * An unedited outlet is left out of the save entirely rather than sent back
     * with the figure it already had: re-writing every row would touch
     * updated_at on outlets nobody looked at, and that timestamp is the only
     * evidence of when a rep last thought about one.
     */
    val changed: Map<String, Long>
        get() = edits.filter { (id, amount) ->
            val stop = stops.firstOrNull { it.customerId == id } ?: return@filter false
            amount != stop.target || (!stop.hasTarget && amount > 0)
        }

    val canSave: Boolean get() = changed.isNotEmpty()

    /**
     * The figure offered when the rep taps the suggestion: what they sold there
     * last time.
     *
     * Not a computed recommendation. It is a fact about this outlet relabelled as
     * a starting point, and a rep can tell at a glance whether it is one — which
     * they could not do with a number an algorithm produced for them.
     */
    fun suggestionFor(stop: DailyTargetStop): Long? = stop.lastAmount?.takeIf { it > 0 }
}
