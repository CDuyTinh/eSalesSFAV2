package com.tinhcd.myesalessfa.data.remote.service

import com.tinhcd.myesalessfa.data.remote.dto.CustomerOptionsDto
import com.tinhcd.myesalessfa.data.remote.dto.NewCustomerDto
import com.tinhcd.myesalessfa.data.remote.dto.RegisteredCustomerAckDto
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

/**
 * The registration form's data: what fills its dropdowns, and where it is sent.
 *
 * The three option calls share one endpoint because they are one question asked
 * at three depths — which segments exist, which districts are in this province,
 * which wards are in this district — and three functions would have been three
 * deployments to keep in step.
 */
interface CustomerRegistrationService {

    @GET("customer-options")
    suspend fun options(): Response<CustomerOptionsDto>

    @GET("customer-options")
    suspend fun districts(@Query("province_id") provinceId: String): Response<CustomerOptionsDto>

    @GET("customer-options")
    suspend fun wards(@Query("district_id") districtId: String): Response<CustomerOptionsDto>

    @POST("submit-customer")
    suspend fun submit(@Body customer: NewCustomerDto): Response<RegisteredCustomerAckDto>
}
