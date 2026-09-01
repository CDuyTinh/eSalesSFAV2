package com.tinhcd.myesalessfa.data.repository

import com.tinhcd.myesalessfa.data.remote.dto.CustomerInfoDto
import com.tinhcd.myesalessfa.data.remote.http.orThrow
import com.tinhcd.myesalessfa.data.remote.service.CustomerService
import com.tinhcd.myesalessfa.domain.DataResult
import com.tinhcd.myesalessfa.domain.model.CustomerInfo
import com.tinhcd.myesalessfa.domain.repository.CustomerRepository
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
}

private fun CustomerInfoDto.toDomain() = CustomerInfo(
    customerId = customerId,
    code = code,
    name = name,
    phone = phone,
    address = address,
    avatarUrl = avatarUrl,
    contactName = contactName,
    channelName = channelName,
    className = className,
    shopTypeName = shopTypeName,
    creditLimit = creditLimit,
    monthRevenue = monthRevenue,
)
