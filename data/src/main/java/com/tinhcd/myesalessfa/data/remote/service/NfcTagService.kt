package com.tinhcd.myesalessfa.data.remote.service

import com.tinhcd.myesalessfa.data.remote.dto.NfcLookupDto
import com.tinhcd.myesalessfa.data.remote.dto.NfcRegisterDto
import com.tinhcd.myesalessfa.data.remote.dto.NfcRegistrationDto
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

/** `nfc-tag`: outlet NFC tags. */
interface NfcTagService {

    @GET("nfc-tag")
    suspend fun lookup(@Query("uid") uid: String): Response<NfcLookupDto>

    @POST("nfc-tag")
    suspend fun register(@Body body: NfcRegisterDto): Response<NfcRegistrationDto>
}
