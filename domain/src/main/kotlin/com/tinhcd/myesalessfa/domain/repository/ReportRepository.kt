package com.tinhcd.myesalessfa.domain.repository

import com.tinhcd.myesalessfa.domain.DataResult
import com.tinhcd.myesalessfa.domain.model.ActivityReport
import com.tinhcd.myesalessfa.domain.model.OrderReport
import com.tinhcd.myesalessfa.domain.model.OrderReportStatus
import com.tinhcd.myesalessfa.domain.model.ReportWindow
import com.tinhcd.myesalessfa.domain.model.SalesReport
import com.tinhcd.myesalessfa.domain.model.SkuReport
import com.tinhcd.myesalessfa.domain.model.TradeReport
import com.tinhcd.myesalessfa.domain.model.WorkResultReport
import java.time.LocalDate

/** The six reports on the rep's menu. */
interface ReportRepository {

    suspend fun activities(date: LocalDate): DataResult<ActivityReport>

    /** Any day in the month; the server truncates to the period. */
    suspend fun sales(month: LocalDate): DataResult<SalesReport>

    suspend fun orders(
        window: ReportWindow,
        statuses: List<OrderReportStatus> = emptyList(),
        inRoute: Boolean? = null,
    ): DataResult<OrderReport>

    suspend fun sku(window: ReportWindow, query: String = ""): DataResult<SkuReport>

    suspend fun trade(window: ReportWindow): DataResult<TradeReport>

    suspend fun workResult(window: ReportWindow): DataResult<WorkResultReport>
}
