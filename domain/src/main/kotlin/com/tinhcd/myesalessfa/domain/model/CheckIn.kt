package com.tinhcd.myesalessfa.domain.model

enum class ReasonKind {
    NO_ORDER,
    OUTLET_CLOSED,
    GPS_OUT_OF_RANGE,
    GPS_LOW_ACCURACY,
    GPS_UNAVAILABLE,
    PHOTO_SKIPPED,
}

data class ReasonCode(
    val id: String,
    val code: String,
    val name: String,
    val kind: ReasonKind,
)

/**
 * Whether the rep standing outside a shop is allowed to check in, and on what
 * terms. Lives in :domain because it is a business rule, not a UI concern —
 * the screen only renders the verdict.
 */
sealed interface CheckInGate {
    /** Close enough, accurate enough. Go. */
    data class Allowed(val distanceM: Double) : CheckInGate

    /**
     * Something is off, but policy lets the rep proceed after explaining why.
     * The legacy app called this "chọn lý do".
     */
    data class NeedsReason(
        val kind: ReasonKind,
        val distanceM: Double?,
    ) : CheckInGate

    /** Policy forbids it outright. */
    data class Blocked(
        val kind: ReasonKind,
        val distanceM: Double?,
    ) : CheckInGate
}

/** The knobs a check-in is judged against, read from app_setting. */
data class CheckInPolicy(
    val defaultRadiusM: Int,
    val maxAccuracyM: Int,
    val allowReasonWhenFar: Boolean,
) {
    companion object {
        /**
         * Used when settings have not loaded yet. Deliberately strict: better
         * to ask for a reason we did not need than to silently accept a
         * check-in from the wrong side of town.
         */
        val Fallback = CheckInPolicy(
            defaultRadiusM = 100,
            maxAccuracyM = 50,
            allowReasonWhenFar = true,
        )
    }
}
