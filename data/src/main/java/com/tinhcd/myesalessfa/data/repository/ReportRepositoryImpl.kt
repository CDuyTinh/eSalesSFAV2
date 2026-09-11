package com.tinhcd.myesalessfa.data.repository

import com.tinhcd.myesalessfa.data.remote.dto.ActivityReportDto
import com.tinhcd.myesalessfa.data.remote.dto.ActivityRowDto
import com.tinhcd.myesalessfa.data.remote.dto.CustomerSalesDto
import com.tinhcd.myesalessfa.data.remote.dto.OrderReportRowDto
import com.tinhcd.myesalessfa.data.remote.dto.ProductSalesDto
import com.tinhcd.myesalessfa.data.remote.dto.SkuReportRowDto
import com.tinhcd.myesalessfa.data.remote.dto.TradeReportRowDto
import com.tinhcd.myesalessfa.data.remote.dto.WorkResultRowDto
import com.tinhcd.myesalessfa.data.remote.dto.SalesReportDto
import com.tinhcd.myesalessfa.data.remote.http.orThrow
import com.tinhcd.myesalessfa.data.remote.service.ReportService
import com.tinhcd.myesalessfa.domain.DataResult
import com.tinhcd.myesalessfa.domain.model.ActivityReport
import com.tinhcd.myesalessfa.domain.model.ActivityRow
import com.tinhcd.myesalessfa.domain.model.ActivitySummary
import com.tinhcd.myesalessfa.domain.model.CustomerSales
import com.tinhcd.myesalessfa.domain.model.OrderReport
import com.tinhcd.myesalessfa.domain.model.OrderReportRow
import com.tinhcd.myesalessfa.domain.model.OrderReportStatus
import com.tinhcd.myesalessfa.domain.model.ProductSales
import com.tinhcd.myesalessfa.domain.model.ReportWindow
import com.tinhcd.myesalessfa.domain.model.SkuReport
import com.tinhcd.myesalessfa.domain.model.SkuReportRow
import com.tinhcd.myesalessfa.domain.model.TradeReport
import com.tinhcd.myesalessfa.domain.model.TradeReportKind
import com.tinhcd.myesalessfa.domain.model.TradeReportRow
import com.tinhcd.myesalessfa.domain.model.WorkPlanStatus
import com.tinhcd.myesalessfa.domain.model.WorkResultReport
import com.tinhcd.myesalessfa.domain.model.WorkResultRow
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

    override suspend fun orders(
        window: ReportWindow,
        statuses: List<OrderReportStatus>,
        inRoute: Boolean?,
    ): DataResult<OrderReport> = try {
        val body = service.orders(
            fromDate = window.fromDate.toString(),
            toDate = window.toDate.toString(),
            statuses = statuses.takeIf { it.isNotEmpty() }?.joinToString(",") { it.wire },
            inRoute = inRoute,
        ).orThrow()

        DataResult.Success(
            OrderReport(
                total = body.total,
                totalAmount = body.totalAmount,
                // An unknown status name is dropped rather than mapped onto a
                // real one: a wrong count is worse than a missing chip.
                counts = body.counts.mapNotNull { (wire, n) ->
                    OrderReportStatus.entries.firstOrNull { it.wire == wire }?.let { it to n }
                }.toMap(),
                items = body.items.map { it.toDomain() },
            ),
        )
    } catch (e: Exception) {
        DataResult.Failure(e.toAppError())
    }

    override suspend fun sku(window: ReportWindow, query: String): DataResult<SkuReport> = try {
        DataResult.Success(
            SkuReport(
                items = service.sku(
                    fromDate = window.fromDate.toString(),
                    toDate = window.toDate.toString(),
                    query = query.trim().ifBlank { null },
                ).orThrow().items.map { it.toDomain() },
            ),
        )
    } catch (e: Exception) {
        DataResult.Failure(e.toAppError())
    }

    override suspend fun trade(window: ReportWindow): DataResult<TradeReport> = try {
        val body = service.trade(
            fromDate = window.fromDate.toString(),
            toDate = window.toDate.toString(),
        ).orThrow()

        DataResult.Success(
            TradeReport(
                displayCount = body.displayCount,
                loyaltyCount = body.loyaltyCount,
                customerCount = body.customerCount,
                items = body.items.map { it.toDomain() },
            ),
        )
    } catch (e: Exception) {
        DataResult.Failure(e.toAppError())
    }

    override suspend fun workResult(window: ReportWindow): DataResult<WorkResultReport> = try {
        val body = service.workResult(
            fromDate = window.fromDate.toString(),
            toDate = window.toDate.toString(),
        ).orThrow()

        DataResult.Success(
            WorkResultReport(
                completed = body.completed,
                cancelled = body.cancelled,
                total = body.total,
                items = body.items.map { it.toDomain() },
            ),
        )
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

private fun OrderReportRowDto.toDomain() = OrderReportRow(
    id = id,
    orderNo = orderNo,
    orderDate = orderDate,
    customerCode = customerCode,
    customerName = customerName,
    status = OrderReportStatus.fromWire(status),
    inRoute = inRoute,
    lineCount = lineCount,
    subTotal = subTotal,
    discountAmount = discountAmount,
    vatAmount = vatAmount,
    totalAmount = totalAmount,
)

private fun SkuReportRowDto.toDomain() = SkuReportRow(
    productId = productId,
    productCode = productCode,
    productName = productName,
    stockUom = stockUom,
    qtyConfirmed = qtyConfirmed,
    qtyPending = qtyPending,
    qtyTotal = qtyTotal,
    amountTotal = amountTotal,
)

private fun TradeReportRowDto.toDomain() = TradeReportRow(
    kind = TradeReportKind.fromWire(kind),
    registeredAt = registeredAt,
    status = status,
    portion = portion,
    customerCode = customerCode,
    customerName = customerName,
    programCode = programCode,
    programName = programName,
    levelName = levelName,
)

private fun WorkResultRowDto.toDomain() = WorkResultRow(
    id = id,
    content = content,
    categoryName = categoryName,
    status = WorkPlanStatus.fromWire(status),
    fromAt = fromAt,
    toAt = toAt,
    objectCount = objectCount,
    outcome = outcome,
)
