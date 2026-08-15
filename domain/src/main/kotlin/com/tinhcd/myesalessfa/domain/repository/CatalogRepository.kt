package com.tinhcd.myesalessfa.domain.repository

import com.tinhcd.myesalessfa.domain.DataResult
import com.tinhcd.myesalessfa.domain.model.PricedProduct
import java.time.LocalDate

interface CatalogRepository {
    /** Pulls products, sale units and the price list into the local cache. */
    suspend fun refresh(): DataResult<Unit>

    /**
     * The catalogue as this customer may buy it: units they have no price for are
     * not offered, because the server refuses to book an unpriced line.
     *
     * @param on the order date, since prices are effective-dated.
     */
    suspend fun catalogue(
        classId: String?,
        on: LocalDate,
    ): DataResult<List<PricedProduct>>

    /**
     * Par levels this outlet is obliged to stock, as product id -> base units.
     *
     * Resolved from the cached lists rather than fetched per customer, so the stock
     * screen can mark required SKUs with no signal. Empty when no list covers the
     * outlet's segment.
     */
    suspend fun mustStock(
        channelId: String?,
        shopTypeId: String?,
        on: LocalDate,
    ): DataResult<Map<String, Int>>
}
