package com.project.saaya.data.remote

import com.project.saaya.data.auth.SessionManager
import com.project.saaya.data.local.AuthSessionStore
import com.project.saaya.data.model.AuthSession
import com.project.saaya.data.model.AuthenticatedUser
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class AuthInterceptorTest {
    private val user = AuthenticatedUser("real-stable-owner", "Ananya", "ananya@example.com")
    private val store = object : AuthSessionStore {
        var value: AuthSession? = null
        override fun read() = value
        override fun write(session: AuthSession) { value = session }
        override fun clear() { value = null }
    }
    private val sessions = SessionManager(store)

    private fun request(url: String, code: Int = 200, beforeResponse: () -> Unit = {}): Request {
        var sent: Request? = null
        val client = OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor(sessions))
            .addInterceptor { chain ->
                sent = chain.request()
                beforeResponse()
                okhttp3.Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(code).message("Fixture").body("".toResponseBody()).build()
            }.build()
        client.newCall(Request.Builder().url(url).build()).execute().use { }
        return sent!!
    }

    @Test fun attachesBearerOnlyToRenderHttpsProtectedEndpoints() {
        sessions.activate(AuthSession("session-fixture", user, 200))
        assertEquals("Bearer session-fixture", request("https://narikavach-backend.onrender.com/contacts?ownerId=${user.id}").header("Authorization"))
        assertNull(request("https://narikavach-backend.onrender.com/users/google").header("Authorization"))
        assertNull(request("https://other.example/contacts").header("Authorization"))
        assertNull(request("http://narikavach-backend.onrender.com/contacts").header("Authorization"))
    }

    @Test fun unauthenticatedRequestHasNoBearer() {
        assertNull(request("https://narikavach-backend.onrender.com/contacts").header("Authorization"))
    }

    @Test fun unauthorizedClearsSessionAndNotifiesAuthenticationGate() {
        var invalidated = false
        sessions.onInvalidated = { invalidated = true }
        sessions.activate(AuthSession("session-fixture", user, 200))
        request("https://narikavach-backend.onrender.com/contacts", 401)
        assertNull(sessions.current)
        assertNull(store.read())
        assertTrue(invalidated)
    }

    @Test fun lateUnauthorizedFromOldSessionDoesNotInvalidateNewAccount() {
        sessions.activate(AuthSession("old-session-fixture", user, 200))
        request("https://narikavach-backend.onrender.com/contacts", 401) {
            sessions.activate(AuthSession("new-session-fixture", user.copy(id = "other-owner"), 200))
        }
        assertEquals("other-owner", sessions.current!!.user.id)
        assertEquals("new-session-fixture", store.read()!!.token)
    }
}
