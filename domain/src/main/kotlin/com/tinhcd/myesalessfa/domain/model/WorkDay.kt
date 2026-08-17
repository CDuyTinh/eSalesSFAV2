package com.tinhcd.myesalessfa.domain.model

import java.time.LocalDate
import java.time.LocalTime

/**
 * The depot a rep starts and ends the day at.
 *
 * Carries a position because the clock-in is measured against it, the same way an
 * outlet check-in is measured against the shop. A branch with no position recorded
 * is normal in a market that has not geocoded its depots, and is handled the way
 * an ungeocoded customer is: let the punch through rather than trap the rep.
 */
data class Branch(
    val id: String,
    val code: String,
    val name: String,
    val address: String?,
    val lat: Double?,
    val lng: Double?,
)

/**
 * How far through the selling day the rep is.
 *
 * Three states rather than a pair of booleans: "not started" and "finished" both
 * have no open day, and code that asks `isOpen` would treat them the same when
 * they call for opposite things on screen — one offers a way in, the other says
 * the day is done.
 */
enum class WorkDayState {
    NOT_STARTED,
    OPEN,
    CLOSED,
}

/**
 * One rep's selling day: where it happens, and the two punches that bound it.
 *
 * [openVisits] is carried here because it is what decides whether the day can be
 * closed, and the rep should learn that before they press the button rather than
 * from a refusal afterwards.
 */
data class WorkDay(
    val date: LocalDate,
    val branch: Branch,
    val checkInAtEpochMs: Long?,
    val checkOutAtEpochMs: Long?,
    val openVisits: Int,
) {
    val state: WorkDayState
        get() = when {
            checkInAtEpochMs == null -> WorkDayState.NOT_STARTED
            checkOutAtEpochMs == null -> WorkDayState.OPEN
            else -> WorkDayState.CLOSED
        }

    val isOpen: Boolean get() = state == WorkDayState.OPEN

    /** The day cannot be closed over a visit the rep is still standing inside. */
    val canCloseDay: Boolean get() = isOpen && openVisits == 0
}

/**
 * What a punch at the depot is judged against, read from app_setting.
 *
 * Separate from [CheckInPolicy] despite sharing two of its three knobs: a depot is
 * not a shop. The radius is its own setting because a depot yard is bigger than a
 * shopfront, and lateness only means anything about the start of a day.
 */
data class WorkDayPolicy(
    val branchRadiusM: Int,
    val maxAccuracyM: Int,
    val allowReasonWhenFar: Boolean,
    /** Clocking in after this is flagged. Null when the market does not track it. */
    val lateAfter: LocalTime?,
) {
    companion object {
        /** Used before settings load. Strict for the same reason [CheckInPolicy] is. */
        val Fallback = WorkDayPolicy(
            branchRadiusM = 200,
            maxAccuracyM = 50,
            allowReasonWhenFar = true,
            lateAfter = null,
        )
    }
}

/** Everything captured at the moment a rep opens or closes their selling day. */
data class WorkDayPunch(
    val date: LocalDate,
    val point: GeoPoint?,
    val distanceM: Double?,
    val reasonId: String?,
)
