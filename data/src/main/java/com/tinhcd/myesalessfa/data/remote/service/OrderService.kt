package com.tinhcd.myesalessfa.data.remote.service

import com.tinhcd.myesalessfa.data.remote.dto.CartDto
import com.tinhcd.myesalessfa.data.remote.dto.CartPayload
import com.tinhcd.myesalessfa.data.remote.dto.OrderPayload
import com.tinhcd.myesalessfa.data.remote.dto.WriteAckDto
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

interface OrderService {

    /**
     * Forwards to the `submit_order` database function, which prices the order and
     * writes header, lines and the take_order step in one transaction.
     */
    @POST("submit-order")
    suspend fun submitOrder(@Body order: OrderPayload): Response<WriteAckDto>

    /** The basket this rep has building for one outlet. */
    @GET("cart")
    suspend fun cart(@Query("customerId") customerId: String): CartDto

    /** Makes the stored basket match what is sent. */
    @POST("cart")
    suspend fun saveCart(@Body cart: CartPayload): Response<WriteAckDto>
}
