package com.tinhcd.myesalessfa.data.repository

import com.tinhcd.myesalessfa.data.remote.dto.ActivityReportDto
import com.tinhcd.myesalessfa.data.remote.dto.ActivityRowDto
import com.tinhcd.myesalessfa.data.remote.dto.CustomerSalesDto
import com.tinhcd.myesalessfa.data.remote.dto.ProductSalesDto
import com.tinhcd.myesalessfa.data.remote.dto.SalesReportDto
import com.tinhcd.myesalessfa.data.remote.http.orThrow
import com.tinhcd.myesalessfa.data.remote.service.ReportService
import com.tinhcd.myesalessfa.domain.DataResult
import com.tinhcd.myesalessfa.domain.model.ActivityReport
import com.tinhcd.myesalessfa.domain.model.ActivityRow
import com.tinhcd.myesalessfa.domain.model.ActivitySummary
import com.tinhcd.myesalessfa.domain.model.CustomerSales
import com.tinhcd.myesalessfa.domain.model.ProductSales
import com.tinhcd.myesalessfa.domain.model.SalesReport
import com.tinhcd.myesalessfa.domain.model.VisitStatus
import com.tinhcd.myesalessfa.domain.repository.ReportRepository
import java.time.LocalDate
import java.time.OffsetDateTime
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Not cached. A report is read a handful of times a day and is about figures that
 * move all day; a stale copy would be worse than the second it takes to fetch.
 */
@Singleton
class ReportRepositoryImpl @Inject constructor(
    private val service: ReportService,
) : ReportRepository {

    override suspend fun activities(date: LocalDate): DataResult<ActivityReport> = try {
        DataResult.Success(service.activities(date.toString()).orThrow().toDomain())
    } catch (e: Exception) {
        DataResult.Failure(e.toAppError())
    }

    override suspend fun sales(month: LocalDate): DataResult<SalesReport> = try {
        DataResult.Success(service.sales(month.toString()).orThrow().toDomain())
    } catch (e: Exception) {
        DataResult.Failure(e.toAppError())
    }
}

private fun ActivityReportDto.toDomain() = ActivityReport(
    date = LocalDate.parse(date),
    summary = ActivitySummary(
        planned = summary.planned,
        visited = summary.visited,
        unplanned = summary.unplanned,
        strike = summary.strike,
        nonStrike = summary.nonStrike,
        closed = summary.closed,
        orderAmount = summary.orderAmount,
    ),
    rows = rows.map { it.toDomain() },
)

private fun ActivityRowDto.toDomain() = ActivityRow(
    visitId = visitId,
    customerCode = customerCode,
    customerName = customerName,
    address = address,
    planned = planned,
    status = status.toVisitStatus(),
    checkInAtEpochMs = checkInAt?.toEpochMillis(),
    checkOutAtEpochMs = checkOutAt?.toEpochMillis(),
    minutes = minutes,
    orderAmount = orderAmount,
)

private fun SalesReportDto.toDomain() = SalesReport(
    month = LocalDate.parse(month),
    revenue = revenue,
    orderCount = orderCount,
    target = target,
    customers = customers.map { it.toDomain() },
    products = products.map { it.toDomain() },
)

private fun CustomerSalesDto.toDomain() = CustomerSales(
    customerCode = customerCode,
    customerName = customerName,
    orders = orders,
    revenue = revenue,
)

private fun ProductSalesDto.toDomain() = ProductSales(
    productCode = productCode,
    productName = productName,
    baseUom = baseUom,
    baseQty = baseQty,
    revenue = revenue,
)

private fun String.toVisitStatus(): VisitStatus = when (this) {
    "in_progress" -> VisitStatus.IN_PROGRESS
    "completed" -> VisitStatus.COMPLETED
    "no_order" -> VisitStatus.NO_ORDER
    "closed" -> VisitStatus.CLOSED
    "abandoned" -> VisitStatus.ABANDONED
    else -> VisitStatus.PLANNED
}

private fun String.toEpochMillis(): Long? =
    runCatching { OffsetDateTime.parse(this).toInstant().toEpochMilli() }.getOrNull()
