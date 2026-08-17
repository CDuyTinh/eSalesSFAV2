package com.tinhcd.myesalessfa.domain.repository

import com.tinhcd.myesalessfa.domain.DataResult
import com.tinhcd.myesalessfa.domain.model.ActivityReport
import com.tinhcd.myesalessfa.domain.model.SalesReport
import java.time.LocalDate

/** The two reports a rep is asked about: their day, and their month. */
interface ReportRepository {

    suspend fun activities(date: LocalDate): DataResult<ActivityReport>

    /** Any day in the month; the server truncates to the period. */
    suspend fun sales(month: LocalDate): DataResult<SalesReport>
}
