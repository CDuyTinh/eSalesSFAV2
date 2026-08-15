package com.tinhcd.myesalessfa.data.remote.service

import com.tinhcd.myesalessfa.data.remote.dto.OrderPayload
import com.tinhcd.myesalessfa.data.remote.dto.WriteAckDto
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST

interface OrderService {

    /**
     * Forwards to the `submit_order` database function, which prices the order and
     * writes header, lines and the take_order step in one transaction.
     */
    @POST("submit-order")
    suspend fun submitOrder(@Body order: OrderPayload): Response<WriteAckDto>
}
