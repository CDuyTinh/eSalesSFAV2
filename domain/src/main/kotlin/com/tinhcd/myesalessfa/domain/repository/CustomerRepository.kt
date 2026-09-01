package com.tinhcd.myesalessfa.domain.repository

import com.tinhcd.myesalessfa.domain.DataResult
import com.tinhcd.myesalessfa.domain.model.CustomerInfo

interface CustomerRepository {
    /**
     * One outlet's detail card.
     *
     * Read from the server every time. The month's revenue moves with every order
     * the rep books, and a cached card would tell a shop it had bought less than
     * it just did.
     */
    suspend fun info(customerId: String): DataResult<CustomerInfo>
}
