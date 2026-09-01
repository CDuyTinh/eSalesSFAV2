package com.tinhcd.myesalessfa.data.repository

import com.tinhcd.myesalessfa.data.remote.dto.CustomerInfoDto
import com.tinhcd.myesalessfa.data.remote.dto.CustomerOrderDto
import com.tinhcd.myesalessfa.data.remote.dto.CustomerOrderLineDto
import com.tinhcd.myesalessfa.data.remote.http.orThrow
import com.tinhcd.myesalessfa.data.remote.service.CustomerService
import com.tinhcd.myesalessfa.domain.DataResult
import com.tinhcd.myesalessfa.domain.model.CustomerInfo
import com.tinhcd.myesalessfa.domain.model.CustomerOrder
import com.tinhcd.myesalessfa.domain.model.CustomerOrderLine
import com.tinhcd.myesalessfa.domain.repository.CustomerRepository
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CustomerRepositoryImpl @Inject constructor(
    private val service: CustomerService,
) : CustomerRepository {

    override suspend fun info(customerId: String): DataResult<CustomerInfo> = try {
        DataResult.Success(service.info(customerId).orThrow().toDomain())
    } catch (e: Exception) {
        DataResult.Failure(e.toAppError())
    }

    override suspend fun orders(
        customerId: String,
        limit: Int,
    ): DataResult<List<CustomerOrder>> = try {
        DataResult.Success(
            service.orders(customerId, limit).orThrow().orders.mapNotNull { it.toDomain() },
        )
    } catch (e: Exception) {
        DataResult.Failure(e.toAppError())
    }
}

private fun CustomerInfoDto.toDomain() = CustomerInfo(
    customerId = customerId,
    code = code,
    name = name,
    phone = phone,
    address = address,
    avatarUrl = avatarUrl,
    lat = lat,
    lng = lng,
    contactName = contactName,
    channelName = channelName,
    className = className,
    shopTypeName = shopTypeName,
    creditLimit = creditLimit,
    monthRevenue = monthRevenue,
)

/**
 * Null when the date will not parse, and the caller drops the row.
 *
 * An order with no readable date cannot be placed in a list whose whole point is
 * chronological, and showing it undated next to real ones invites the rep to
 * read it as the most recent.
 */
private fun CustomerOrderDto.toDomain(): CustomerOrder? {
    val date = runCatching { LocalDate.parse(orderDate) }.getOrNull() ?: return null
    return CustomerOrder(
        orderId = orderId,
        orderNo = orderNo,
        orderDate = date,
        status = status,
        totalAmount = totalAmount,
        lines = lines.map { it.toDomain() },
    )
}

private fun CustomerOrderLineDto.toDomain() = CustomerOrderLine(
    productCode = productCode,
    productName = productName,
    uomCode = uomCode,
    qty = qty,
    lineAmount = lineAmount,
)
