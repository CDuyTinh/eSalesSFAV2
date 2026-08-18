package com.tinhcd.myesalessfa.domain.repository

import com.tinhcd.myesalessfa.domain.DataResult
import com.tinhcd.myesalessfa.domain.model.DailyTargetStop
import java.time.LocalDate

/** The rep's own plan for the day: how much they mean to sell at each stop. */
interface DailyTargetRepository {

    suspend fun stops(date: LocalDate): DataResult<List<DailyTargetStop>>

    /** Only the outlets that changed; the whole batch lands or none of it does. */
    suspend fun save(date: LocalDate, targets: Map<String, Long>): DataResult<Unit>
}
