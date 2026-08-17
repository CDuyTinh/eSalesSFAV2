package com.tinhcd.myesalessfa.data.remote.service

import com.tinhcd.myesalessfa.data.remote.dto.CollectPaymentDto
import com.tinhcd.myesalessfa.data.remote.dto.ReceivableCustomersDto
import com.tinhcd.myesalessfa.data.remote.dto.ReceivableInvoicesDto
import com.tinhcd.myesalessfa.data.remote.dto.WriteAckDto
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

interface ReceivableService {

    @GET("receivables")
    suspend fun customers(): Response<ReceivableCustomersDto>

    @GET("receivables")
    suspend fun invoices(@Query("customer_id") customerId: String): Response<ReceivableInvoicesDto>

    /**
     * Returns [Response] because the refusal is the useful part: the overpayment
     * trigger names the invoice's remaining balance, which is exactly the figure
     * the rep needs to correct what they were about to record.
     */
    @POST("receivables")
    suspend fun collect(@Body payment: CollectPaymentDto): Response<WriteAckDto>
}
