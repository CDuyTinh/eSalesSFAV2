package com.tinhcd.myesalessfa.domain.repository

import com.tinhcd.myesalessfa.domain.DataResult
import com.tinhcd.myesalessfa.domain.model.CustomerInfo
import com.tinhcd.myesalessfa.domain.model.CustomerOrder

interface CustomerRepository {
    /**
     * One outlet's detail card.
     *
     * Read from the server every time. The month's revenue moves with every order
     * the rep books, and a cached card would tell a shop it had bought less than
     * it just did.
     */
    suspend fun info(customerId: String): DataResult<CustomerInfo>

    /**
     * Recent orders at this outlet, newest first, cancelled ones excluded.
     *
     * Separate call from [info] because the history tab is not always opened, and
     * a rep who only wanted the phone number should not wait for order lines.
     */
    suspend fun orders(customerId: String, limit: Int = 20): DataResult<List<CustomerOrder>>
}
