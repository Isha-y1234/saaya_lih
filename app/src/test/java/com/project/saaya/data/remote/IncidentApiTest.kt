package com.project.saaya.data.remote

import com.google.gson.JsonParser
import com.project.saaya.data.auth.SessionManager
import com.project.saaya.data.local.AuthSessionStore
import com.project.saaya.data.model.AuthSession
import com.project.saaya.data.model.AuthenticatedUser
import com.project.saaya.data.repository.IncidentRepository
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

class IncidentApiTest {
    @Test fun usesExistingRenderAuthAndIncidentContractWithoutClientOwner() = runBlocking {
        val requests = mutableListOf<Request>()
        val bodies = mutableListOf<String>()
        val owner = "stable-owner"
        val sessions = SessionManager(object : AuthSessionStore {
            override fun read(): AuthSession? = null
            override fun write(session: AuthSession) {}
            override fun clear() {}
        })
        sessions.activate(AuthSession("session-fixture", AuthenticatedUser(owner, "Test", "test@example.com"), 2000000000))
        val client = OkHttpClient.Builder().addInterceptor(AuthInterceptor(sessions)).addInterceptor { chain ->
            val request = chain.request()
            requests.add(request)
            val buffer = Buffer()
            request.body?.writeTo(buffer)
            bodies.add(buffer.readUtf8())
            val ended = request.method == "PATCH"
            val json = """{"id":"backend-incident","ownerId":"$owner","status":"${if (ended) "ENDED" else "ACTIVE"}","createdAt":"2026-10-02T10:00:00Z","updatedAt":"2026-10-02T10:00:00Z","startedAt":"2026-10-02T10:00:00Z","endedAt":${if (ended) "\"2026-10-02T11:00:00Z\"" else "null"},"latitude":12.9,"longitude":77.5}"""
            val payload = if (request.method == "GET" && request.url.encodedPath == "/incidents") """{"incidents":[$json]}""" else json
            okhttp3.Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(if (request.method == "POST") 201 else 200).message("OK")
                .body(payload.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val api = Retrofit.Builder().baseUrl("https://narikavach-backend.onrender.com/").client(client)
            .addConverterFactory(GsonConverterFactory.create()).build().create(IncidentApiService::class.java)
        val repository = IncidentRepository(api, owner)
        val created = repository.createIncident(12.9, 77.5)
        repository.getIncident(created.id)
        repository.loadIncidents()
        repository.endIncident(created.id)
        assertEquals(listOf("POST", "GET", "GET", "PATCH"), requests.map { it.method })
        assertEquals(listOf("/incidents", "/incidents/backend-incident", "/incidents", "/incidents/backend-incident/end"), requests.map { it.url.encodedPath })
        requests.forEach {
            assertEquals("https", it.url.scheme)
            assertEquals("narikavach-backend.onrender.com", it.url.host)
            assertEquals("Bearer session-fixture", it.header("Authorization"))
            assertNull(it.url.queryParameter("ownerId"))
        }
        val post = JsonParser.parseString(bodies.first()).asJsonObject
        assertEquals(setOf("latitude", "longitude"), post.keySet())
        assertEquals(12.9, post["latitude"].asDouble, 0.0)
    }
}
