package com.tinhcd.myesalessfa.data.remote.service

import com.tinhcd.myesalessfa.data.remote.dto.DeviceTokenDto
import com.tinhcd.myesalessfa.data.remote.dto.WriteAckDto
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.HTTP
import retrofit2.http.POST

/** This phone's FCM token, filed against whoever is signed in (`device-token`). */
interface DeviceTokenService {

    @POST("device-token")
    suspend fun register(@Body body: DeviceTokenDto): Response<WriteAckDto>

    /** A DELETE with a body: the token is what is being removed, not a path segment. */
    @HTTP(method = "DELETE", path = "device-token", hasBody = true)
    suspend fun unregister(@Body body: DeviceTokenDto): Response<WriteAckDto>
}
