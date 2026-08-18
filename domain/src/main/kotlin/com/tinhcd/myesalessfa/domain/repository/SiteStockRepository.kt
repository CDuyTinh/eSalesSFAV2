package com.tinhcd.myesalessfa.domain.repository

import com.tinhcd.myesalessfa.domain.DataResult
import com.tinhcd.myesalessfa.domain.model.SiteStockView

/** What the distributor has on hand. Read-only: the app is not the warehouse. */
interface SiteStockRepository {

    /** Null asks for the branch's first warehouse, which is the common case. */
    suspend fun load(siteId: String?): DataResult<SiteStockView>
}
