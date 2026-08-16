package com.tinhcd.myesalessfa.domain.repository

import com.tinhcd.myesalessfa.domain.DataResult
import com.tinhcd.myesalessfa.domain.model.DashboardOverview
import java.time.LocalDate

interface DashboardRepository {
    /**
     * The overview for one day, and the month and weeks around it.
     *
     * Never cached. Every other reference read in this app is something head
     * office changes rarely; these are the rep's own figures from minutes ago,
     * and a stale sales total is the one number on the screen worth nothing.
     */
    suspend fun overview(date: LocalDate): DataResult<DashboardOverview>
}
