package com.project.saaya.data.remote

import com.google.gson.JsonParser
import com.project.saaya.data.auth.SessionManager
import com.project.saaya.data.local.AuthSessionStore
import com.project.saaya.data.model.AuthSession
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class UserApiTest {
    @Test fun googleExchangeAndSessionRestoreUseTheActualJsonContract() = runBlocking {
        val sessions = SessionManager(object : AuthSessionStore {
            override fun read(): AuthSession? = null
            override fun write(session: AuthSession) {}
            override fun clear() {}
        })
        val userJson = """{"_id":"stable-mongo-user","name":"Ananya","email":"ananya@example.com","saayaId":"SAAYA-507F1F77BCF86CD799439011","createdAt":"2026-10-02T00:00:00Z"}"""
        val calls = mutableListOf<String>()
        val client = OkHttpClient.Builder().addInterceptor(AuthInterceptor(sessions)).addInterceptor { chain ->
            val request = chain.request()
            calls.add("${request.method} ${request.url.encodedPath}")
            val body = when (request.url.encodedPath) {
                "/users/google" -> {
                    assertNull(request.header("Authorization"))
                    val buffer = Buffer()
                    request.body!!.writeTo(buffer)
                    val payload = JsonParser.parseString(buffer.readUtf8()).asJsonObject
                    assertEquals(setOf("idToken"), payload.keySet())
                    assertEquals("google-id-token-fixture", payload["idToken"].asString)
                    """{"user":$userJson,"token":"opaque-session-fixture","expiresAt":2000000000}"""
                }
                else -> {
                    assertEquals("Bearer opaque-session-fixture", request.header("Authorization"))
                    userJson
                }
            }
            okhttp3.Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(if (request.method == "DELETE") 204 else 200).message("Fixture")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val api = Retrofit.Builder().baseUrl("https://narikavach-backend.onrender.com/")
            .client(client).addConverterFactory(GsonConverterFactory.create())
            .build().create(UserApiService::class.java)
        val session = api.signIn(GoogleSignInRequest("google-id-token-fixture")).body()!!
        assertEquals("stable-mongo-user", session.user.id)
        assertEquals("SAAYA-507F1F77BCF86CD799439011", session.user.saayaId)
        assertEquals("opaque-session-fixture", session.token)
        assertEquals(2000000000L, session.expiresAt)
        sessions.activate(session)
        assertEquals(session.user, api.getMe("Bearer ${session.token}").body())
        assertEquals(204, api.signOut("Bearer ${session.token}").code())
        assertEquals(listOf("POST /users/google", "GET /users/me", "DELETE /users/session"), calls)
    }
}
