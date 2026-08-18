package com.tinhcd.myesalessfa.data.repository

import com.tinhcd.myesalessfa.data.remote.dto.SiteDto
import com.tinhcd.myesalessfa.data.remote.dto.SiteStockItemDto
import com.tinhcd.myesalessfa.data.remote.http.orThrow
import com.tinhcd.myesalessfa.data.remote.service.SiteStockService
import com.tinhcd.myesalessfa.domain.DataResult
import com.tinhcd.myesalessfa.domain.model.Site
import com.tinhcd.myesalessfa.domain.model.SiteStockItem
import com.tinhcd.myesalessfa.domain.model.SiteStockView
import com.tinhcd.myesalessfa.domain.repository.SiteStockRepository
import java.time.OffsetDateTime
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SiteStockRepositoryImpl @Inject constructor(
    private val service: SiteStockService,
) : SiteStockRepository {

    override suspend fun load(siteId: String?): DataResult<SiteStockView> = try {
        val dto = service.load(siteId).orThrow()
        DataResult.Success(
            SiteStockView(
                sites = dto.sites.map { it.toDomain() },
                siteId = dto.siteId,
                items = dto.items.map { it.toDomain() },
            ),
        )
    } catch (e: Exception) {
        DataResult.Failure(e.toAppError())
    }
}

private fun SiteDto.toDomain() = Site(
    siteId = siteId,
    code = code,
    name = name,
    address = address,
)

private fun SiteStockItemDto.toDomain() = SiteStockItem(
    productId = productId,
    productCode = productCode,
    productName = productName,
    baseUom = baseUom,
    qtyBase = qtyBase,
    updatedAtEpochMs = updatedAt?.let {
        runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull()
    },
)
