package com.project.saaya.data.remote

import com.project.saaya.data.auth.SessionManager
import com.project.saaya.data.local.AuthSessionStore
import com.project.saaya.data.model.AuthSession
import com.project.saaya.data.model.AuthenticatedUser
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class NotificationApiTest {
    @Test fun notificationRoutesUseAuthenticatedReadOnlyAlertContract() = runBlocking {
        val requests = mutableListOf<Request>()
        val sessions = SessionManager(object : AuthSessionStore {
            override fun read(): AuthSession? = null
            override fun write(session: AuthSession) {}
            override fun clear() {}
        })
        sessions.activate(AuthSession("session-fixture", AuthenticatedUser("recipient", "Test", "test@example.com"), 2000000000))
        val incident = "507f1f77bcf86cd799439011"
        val client = OkHttpClient.Builder().addInterceptor(AuthInterceptor(sessions)).addInterceptor { chain ->
            val request = chain.request()
            requests.add(request)
            val payload = """{"id":"$incident","ownerId":"sender","status":"ACTIVE","createdAt":"2026-01-01T00:00:00Z","updatedAt":"2026-01-01T00:00:00Z","startedAt":"2026-01-01T00:00:00Z","endedAt":null}"""
            okhttp3.Response.Builder().request(request).protocol(Protocol.HTTP_1_1).message("OK")
                .code(if (request.method == "DELETE") 204 else 200)
                .body(payload.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val api = Retrofit.Builder().baseUrl("https://narikavach-backend.onrender.com/").client(client)
            .addConverterFactory(GsonConverterFactory.create()).build().create(NotificationApiService::class.java)
        val alert = api.getAlert(incident).body()!!
        assertEquals(incident, alert.id)
        assertEquals("sender", alert.ownerId)
        assertEquals("GET", requests.single().method)
        assertEquals("/incident-alerts/$incident", requests.single().url.encodedPath)
        assertEquals("Bearer session-fixture", requests.single().header("Authorization"))
    }
}
