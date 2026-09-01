package com.tinhcd.myesalessfa.data.remote.service

import com.tinhcd.myesalessfa.data.remote.dto.CustomerInfoDto
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Query

interface CustomerService {

    /**
     * One outlet's detail card.
     *
     * `Response<T>` rather than a bare body: an outlet outside the rep's branch
     * comes back as a 404 with a message, and that is worth showing rather than
     * turning into a generic failure.
     */
    @GET("customer-info")
    suspend fun info(@Query("customer_id") customerId: String): Response<CustomerInfoDto>
}
