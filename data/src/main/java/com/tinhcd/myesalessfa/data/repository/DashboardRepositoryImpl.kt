package com.tinhcd.myesalessfa.data.repository

import com.tinhcd.myesalessfa.data.remote.dto.DashboardChartsDto
import com.tinhcd.myesalessfa.data.remote.dto.DashboardDto
import com.tinhcd.myesalessfa.data.remote.dto.DashboardMonthDto
import com.tinhcd.myesalessfa.data.remote.dto.DashboardTodayDto
import com.tinhcd.myesalessfa.data.remote.dto.SalesPointDto
import com.tinhcd.myesalessfa.data.remote.http.orThrow
import com.tinhcd.myesalessfa.data.remote.service.DashboardService
import com.tinhcd.myesalessfa.domain.DataResult
import com.tinhcd.myesalessfa.domain.model.ChartRange
import com.tinhcd.myesalessfa.domain.model.DashboardOverview
import com.tinhcd.myesalessfa.domain.model.MonthFigures
import com.tinhcd.myesalessfa.domain.model.SalesPoint
import com.tinhcd.myesalessfa.domain.model.TodayFigures
import com.tinhcd.myesalessfa.domain.repository.DashboardRepository
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DashboardRepositoryImpl @Inject constructor(
    private val service: DashboardService,
) : DashboardRepository {

    override suspend fun overview(date: LocalDate): DataResult<DashboardOverview> = try {
        DataResult.Success(service.dashboard(date.toString()).orThrow().toDomain(date))
    } catch (e: Exception) {
        DataResult.Failure(e.toAppError())
    }
}

/**
 * The requested date is kept rather than the one echoed back, and they are the
 * same date — the server is answering the question it was asked. Parsing the
 * echo would only introduce a way for them to differ.
 */
private fun DashboardDto.toDomain(requested: LocalDate) = DashboardOverview(
    date = requested,
    today = today.toDomain(),
    month = month.toDomain(),
    charts = charts.toDomain(),
)

private fun DashboardTodayDto.toDomain() = TodayFigures(
    revenue = revenue,
    orderCount = orderCount,
    visitDone = visitDone,
    visitPlanned = visitPlanned,
    skuPerOrder = skuPerOrder,
)

private fun DashboardMonthDto.toDomain() = MonthFigures(
    revenue = revenue,
    revenueTarget = revenueTarget,
    orderCount = orderCount,
    orderTarget = orderTarget,
)

private fun DashboardChartsDto.toDomain(): Map<ChartRange, List<SalesPoint>> = mapOf(
    ChartRange.THIS_WEEK to thisWeek.map { it.toDomain() },
    ChartRange.LAST_WEEK to lastWeek.map { it.toDomain() },
    ChartRange.THIS_MONTH to thisMonth.map { it.toDomain() },
)

private fun SalesPointDto.toDomain() = SalesPoint(title = title, actual = actual)
