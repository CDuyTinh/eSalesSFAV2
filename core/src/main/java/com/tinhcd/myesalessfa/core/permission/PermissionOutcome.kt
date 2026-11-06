package com.tinhcd.myesalessfa.core.permission

/** What a permission request came back as, from the app's side. */
enum class PermissionOutcome {
    GRANTED,

    /** Refused, but Android will still show its prompt again: explain and offer to ask once more. */
    DENIED,

    /**
     * Refused for good ("don't ask again", or a second refusal on Android 11+):
     * Android answers no without showing anything, so only the app's settings
     * page can turn it back on.
     */
    BLOCKED,
}

/**
 * Reads a permission result.
 *
 * [anyOf] for a set where one is enough (approximate location instead of
 * precise); otherwise every permission must be granted.
 *
 * Android reports "blocked" only indirectly: a refused permission for which it
 * no longer wants a rationale. That is also what it reports the very first time
 * the prompt is dismissed without an answer, so a permission only counts as
 * blocked once it has been asked for before ([askedBefore]); otherwise a rep
 * who tapped outside the prompt once would be sent to Settings for nothing.
 */
fun permissionOutcome(
    results: Map<String, Boolean>,
    anyOf: Boolean,
    canExplain: (String) -> Boolean,
    askedBefore: (String) -> Boolean,
): PermissionOutcome {
    val granted = if (anyOf) results.values.any { it } else results.values.all { it }
    if (granted) return PermissionOutcome.GRANTED

    val refused = results.filterValues { !it }.keys
    return when {
        refused.any(canExplain) -> PermissionOutcome.DENIED
        refused.all(askedBefore) -> PermissionOutcome.BLOCKED
        else -> PermissionOutcome.DENIED
    }
}
