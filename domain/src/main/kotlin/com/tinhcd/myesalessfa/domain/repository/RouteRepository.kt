package com.tinhcd.myesalessfa.domain.repository

import com.tinhcd.myesalessfa.domain.DataResult
import com.tinhcd.myesalessfa.domain.model.RouteStop
import java.time.LocalDate

interface RouteRepository {
    /**
     * Stops scheduled for [date], in visit order.
     *
     * Read straight from the server every time. The route changes through the day as
     * visits are made, and a day's plan is not the sort of rarely-changing reference
     * data the local cache is for.
     */
    suspend fun getRoute(date: LocalDate): DataResult<List<RouteStop>>

    suspend fun getStop(customerId: String, date: LocalDate): DataResult<RouteStop?>
}
