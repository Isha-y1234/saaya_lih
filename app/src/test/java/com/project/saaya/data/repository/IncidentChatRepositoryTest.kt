package com.project.saaya.data.repository

import com.project.saaya.data.auth.SessionManager
import com.project.saaya.data.local.AuthSessionStore
import com.project.saaya.data.model.AuthSession
import com.project.saaya.data.model.AuthenticatedUser
import com.project.saaya.data.remote.AuthInterceptor
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class IncidentChatRepositoryTest {
    private val sessions = SessionManager(object : AuthSessionStore {
        override fun read(): AuthSession? = null
        override fun write(session: AuthSession) {}
        override fun clear() {}
    })
    private fun login(id: String) = sessions.activate(AuthSession("token-$id", AuthenticatedUser(id, id, "$id@example.com"), 2000000000))
    private fun retrofit(code: Int = 200, requests: MutableList<Request> = mutableListOf()): Retrofit {
        val client = OkHttpClient.Builder().addInterceptor(AuthInterceptor(sessions)).addInterceptor { chain ->
            requests.add(chain.request())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("fixture")
                .body("""{"messages":[{"id":"01","incidentId":"incident","senderId":"owner","text":"Persisted","createdAt":"2026-11-03T01:00:00Z"}],"readOnly":true,"nextBefore":null}"""
                    .toResponseBody("application/json".toMediaType())).build()
        }.build()
        return Retrofit.Builder().baseUrl("https://narikavach-backend.onrender.com/").client(client)
            .addConverterFactory(GsonConverterFactory.create()).build()
    }
    @Test fun persistedHistoryUsesSessionAndCursorAndPreservesReadOnly() = runBlocking {
        login("owner")
        val requests = mutableListOf<Request>()
        val repository = IncidentChatRepository(retrofit(requests = requests))
        val page = repository.older("incident", "02")
        assertTrue(page.readOnly)
        assertEquals("Persisted", page.messages.single().text)
        assertEquals("02", requests.single().url.queryParameter("before"))
        assertEquals("/incidents/incident/chat/messages", requests.single().url.encodedPath)
        assertEquals("Bearer token-owner", requests.single().header("Authorization"))
        login("other")
        repository.older("incident", "02")
        assertEquals("Bearer token-other", requests.last().header("Authorization"))
        assertNull(requests.last().url.queryParameter("userId"))
    }
    @Test fun unauthorizedHistoryDoesNotBecomeEmptySuccess() = runBlocking {
        login("owner")
        for (code in listOf(401, 403, 404)) {
            try { IncidentChatRepository(retrofit(code)).older("incident", "02"); fail("Access must fail") }
            catch (error: retrofit2.HttpException) { assertEquals(code, error.code()) }
        }
    }
    @Test fun unauthorizedSocketIsTerminalAndUsesExistingAuth() = runBlocking {
        login("owner")
        val requests = mutableListOf<Request>()
        val frame = withTimeout(5000) { IncidentChatRepository(retrofit(403, requests)).connect("incident").first() }
        assertEquals("denied", frame.type)
        assertEquals("Bearer token-owner", requests.single().header("Authorization"))
        assertEquals("/incidents/incident/chat/ws", requests.single().url.encodedPath)
    }
    @Test fun reconnectMergesMissedHistoryWithoutDuplicatesOrOtherIncidents() {
        fun message(id: String, incident: String = "incident") = IncidentMessage(id, incident, "owner", id, "time")
        val merged = mergeChatMessages(listOf(message("02")), listOf(message("03"), message("02"), message("01"), message("04", "private")), "incident")
        assertEquals(listOf("01", "02", "03"), merged.map { it.id })
        assertEquals(emptyList<IncidentMessage>(), mergeChatMessages(merged, emptyList(), "other-account-incident"))
    }
    @Test fun disconnectedSendFailsWithoutQueuingPrivateText() {
        assertFalse(IncidentChatRepository(retrofit()).send("private"))
    }
}
