package com.tinhcd.myesalessfa.domain.repository

import com.tinhcd.myesalessfa.domain.DataResult
import com.tinhcd.myesalessfa.domain.model.FocusProduct
import java.time.LocalDate

/** What head office is pushing, and how the rep is doing on it. Read-only. */
interface FocusProductRepository {
    suspend fun onDate(date: LocalDate): DataResult<List<FocusProduct>>
}
