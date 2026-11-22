package com.project.saaya.data.remote

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.HTTP
import retrofit2.http.Header
import retrofit2.http.POST

data class DeviceTokenRegistration(val token: String, val notificationsEnabled: Boolean)
data class DeviceTokenRemoval(val token: String)

interface DeviceTokenApiService {
    @POST("devices/token")
    suspend fun register(@Header("Authorization") authorization: String,
                         @Body body: DeviceTokenRegistration): Response<Unit>

    @HTTP(method = "DELETE", path = "devices/token", hasBody = true)
    suspend fun unregister(@Header("Authorization") authorization: String,
                           @Body body: DeviceTokenRemoval): Response<Unit>
}
