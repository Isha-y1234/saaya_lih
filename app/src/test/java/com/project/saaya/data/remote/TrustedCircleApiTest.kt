package com.project.saaya.data.remote

import com.google.gson.JsonParser
import com.project.saaya.data.model.TrustedContact
import com.project.saaya.data.remote.InvitationStatus
import com.project.saaya.data.model.AuthenticatedUser
import com.project.saaya.data.model.AuthSession
import com.project.saaya.data.auth.SessionManager
import com.project.saaya.data.local.AuthSessionStore
import com.project.saaya.data.repository.FastApiTrustedContactJsonMapper
import com.project.saaya.data.repository.TrustedCircleRepository
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class TrustedCircleApiTest {
    @Test fun mapsOutgoingStatusWithoutChangingSavedContactFields() {
        for (status in InvitationStatus.values()) {
            val result = FastApiTrustedContactJsonMapper.decodeContacts(JsonParser.parseString(
                """{"contacts":[{"_id":"saved-id","name":"Mum","relation":"Family","phone":"+91 123 456","initials":"M","invitationStatus":"${status.name}"}]}"""
            )).single()
            assertEquals(status, result.invitationStatus)
            assertEquals("+91 123 456", result.phone)
            assertEquals("saved-id", result.id)
            val newContact = FastApiTrustedContactJsonMapper.encodeNewContact(result, "owner").asJsonObject
            assertEquals(setOf("ownerId", "name", "relation", "phone", "initials"), newContact.keySet())
        }
    }
    @Test
    fun retrofitUsesOwnerQueryAndExactPostFieldsAndBackendIdForDelete() = runBlocking {
        val requests = mutableListOf<Request>()
        val bodies = mutableListOf<String>()
        val id = "507f1f77bcf86cd799439011"
        val user = AuthenticatedUser("stable-mongo-owner", "Mum", "mum@example.com")
        val sessions = SessionManager(object : AuthSessionStore {
            override fun read(): AuthSession? = null
            override fun write(session: AuthSession) {}
            override fun clear() {}
        })
        sessions.activate(AuthSession("opaque-session-fixture", user, 2000000000))
        val json = """{"contacts":[{"_id":"$id","ownerId":"${user.id}","name":"Mum","relation":"Family","phone":"+919876543210","initials":"M","createdAt":"2026-10-02T00:00:00Z"}]}"""
        val client = OkHttpClient.Builder().addInterceptor(AuthInterceptor(sessions)).addInterceptor { chain ->
            val request = chain.request()
            requests.add(request)
            val buffer = Buffer()
            request.body?.writeTo(buffer)
            bodies.add(buffer.readUtf8())
            val code = when (request.method) { "POST" -> 201; "DELETE" -> 204; else -> 200 }
            okhttp3.Response.Builder()
                .request(request).protocol(Protocol.HTTP_1_1).code(code).message("OK")
                .body((if (request.method == "GET") json else "").toResponseBody("application/json".toMediaType()))
                .build()
        }.build()
        val api = Retrofit.Builder().baseUrl("https://narikavach-backend.onrender.com/")
            .client(client).addConverterFactory(GsonConverterFactory.create())
            .build().create(SaayaApiService::class.java)
        val repository = TrustedCircleRepository(api, FastApiTrustedContactJsonMapper, user.id)
        val contact = repository.loadContacts().single()
        assertEquals(id, contact.id)
        assertEquals("Mum", contact.name)
        repository.addContact(contact)
        repository.deleteContact(contact.id)
        assertEquals("/contacts", requests[0].url.encodedPath)
        assertEquals("https", requests[0].url.scheme)
        assertEquals("narikavach-backend.onrender.com", requests[0].url.host)
        assertEquals(user.id, requests[0].url.queryParameter("ownerId"))
        requests.forEach { assertEquals("Bearer opaque-session-fixture", it.header("Authorization")) }
        val post = JsonParser.parseString(bodies[1]).asJsonObject
        assertEquals(setOf("ownerId", "name", "relation", "phone", "initials"), post.keySet())
        assertEquals(user.id, post["ownerId"].asString)
        assertFalse(post.has("_id"))
        assertEquals("/contacts/$id", requests[2].url.encodedPath)
        assertEquals("DELETE", requests[2].method)
    }

    @Test
    fun missingEnvelopeAndMissingBackendIdAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            FastApiTrustedContactJsonMapper.decodeContacts(JsonParser.parseString("{}"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            FastApiTrustedContactJsonMapper.decodeContacts(JsonParser.parseString(
                """{"contacts":[{"name":"Mum","relation":"Family","phone":"123","initials":"M"}]}"""
            ))
        }
        assertEquals(emptyList<TrustedContact>(), FastApiTrustedContactJsonMapper.decodeContacts(
            JsonParser.parseString("""{"contacts":[]} """)
        ))
    }
}
