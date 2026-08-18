package com.tinhcd.myesalessfa.data.repository

import com.tinhcd.myesalessfa.data.remote.dto.FocusProductDto
import com.tinhcd.myesalessfa.data.remote.http.orThrow
import com.tinhcd.myesalessfa.data.remote.service.FocusProductService
import com.tinhcd.myesalessfa.domain.DataResult
import com.tinhcd.myesalessfa.domain.model.FocusProduct
import com.tinhcd.myesalessfa.domain.repository.FocusProductRepository
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FocusProductRepositoryImpl @Inject constructor(
    private val service: FocusProductService,
) : FocusProductRepository {

    override suspend fun onDate(date: LocalDate): DataResult<List<FocusProduct>> = try {
        DataResult.Success(service.onDate(date.toString()).orThrow().products.map { it.toDomain() })
    } catch (e: Exception) {
        DataResult.Failure(e.toAppError())
    }
}

private fun FocusProductDto.toDomain() = FocusProduct(
    focusId = focusId,
    productId = productId,
    productCode = productCode,
    productName = productName,
    baseUom = baseUom,
    fromDate = LocalDate.parse(fromDate),
    toDate = LocalDate.parse(toDate),
    priority = priority,
    targetBaseQty = targetBaseQty,
    note = note,
    soldBaseQty = soldBaseQty,
    outlets = outlets,
)
