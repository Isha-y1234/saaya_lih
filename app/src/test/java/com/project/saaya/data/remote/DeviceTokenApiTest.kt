package com.project.saaya.data.remote

import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class DeviceTokenApiTest {
    @Test fun registerAndRemovalSendBearerAndNoClientChosenOwner() = runBlocking {
        val requests = mutableListOf<Request>()
        val bodies = mutableListOf<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requests.add(request)
            val buffer = Buffer(); request.body?.writeTo(buffer); bodies.add(buffer.readUtf8())
            okhttp3.Response.Builder().request(request).protocol(Protocol.HTTP_1_1).message("OK")
                .code(if (request.method == "DELETE") 204 else 200)
                .body((if (request.method == "DELETE") "" else "{\"registered\":true}")
                    .toResponseBody("application/json".toMediaType())).build()
        }.build()
        val api = Retrofit.Builder().baseUrl("https://narikavach-backend.onrender.com/").client(client)
            .addConverterFactory(GsonConverterFactory.create()).build().create(DeviceTokenApiService::class.java)
        api.register("Bearer session-fixture", DeviceTokenRegistration("fcm-fixture-token", true))
        api.unregister("Bearer session-fixture", DeviceTokenRemoval("fcm-fixture-token"))
        assertEquals(listOf("POST", "DELETE"), requests.map { it.method })
        requests.forEach {
            assertEquals("/devices/token", it.url.encodedPath)
            assertEquals("Bearer session-fixture", it.header("Authorization"))
        }
        val post = JsonParser.parseString(bodies[0]).asJsonObject
        assertEquals(setOf("token", "notificationsEnabled"), post.keySet())
        assertEquals(setOf("token"), JsonParser.parseString(bodies[1]).asJsonObject.keySet())
    }
}
