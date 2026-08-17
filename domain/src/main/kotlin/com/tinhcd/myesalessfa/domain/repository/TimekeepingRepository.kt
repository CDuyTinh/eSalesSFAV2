package com.tinhcd.myesalessfa.domain.repository

import com.tinhcd.myesalessfa.domain.DataResult
import com.tinhcd.myesalessfa.domain.model.WorkDay
import com.tinhcd.myesalessfa.domain.model.WorkDayPunch
import kotlinx.coroutines.flow.StateFlow
import java.time.LocalDate

/**
 * Opening and closing the selling day.
 *
 * Holds the current day as a flow rather than answering one caller at a time,
 * because two screens need the same answer and must not disagree about it: the
 * shell decides whether the route list is reachable at all, and the punch screen
 * decides which of the two buttons to offer. A punch updates the flow, so the
 * shell reacts to the depot being left without anyone wiring a result back
 * through navigation.
 */
interface TimekeepingRepository {

    /** Null until the first successful load; not a claim that the day is unopened. */
    val today: StateFlow<WorkDay?>

    suspend fun refresh(date: LocalDate): DataResult<WorkDay>

    suspend fun openDay(punch: WorkDayPunch): DataResult<Unit>

    suspend fun closeDay(punch: WorkDayPunch): DataResult<Unit>
}
