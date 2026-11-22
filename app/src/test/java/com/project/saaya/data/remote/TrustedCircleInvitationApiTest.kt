package com.project.saaya.data.remote

import com.google.gson.JsonParser
import com.project.saaya.data.repository.FastApiTrustedContactJsonMapper
import com.project.saaya.data.repository.TrustedCircleRepository
import com.project.saaya.data.model.TrustedContact
import java.io.IOException
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class TrustedCircleInvitationApiTest {
    private val contactId = "507f1f77bcf86cd799439011"
    private val invitationId = "a".repeat(32)
    private val saayaId = "SAAYA-507F1F77BCF86CD799439012"
    private val requests = mutableListOf<Request>()
    private val bodies = mutableListOf<String>()
    private var code = 200
    private var response = ""
    private var networkFailure = false
    private val api = Retrofit.Builder().baseUrl("https://example.test/")
        .client(OkHttpClient.Builder().addInterceptor { chain ->
            if (networkFailure) throw IOException("offline")
            val request = chain.request()
            requests.add(request)
            val buffer = Buffer()
            request.body?.writeTo(buffer)
            bodies.add(buffer.readUtf8())
            okhttp3.Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(code).message("fixture")
                .body(response.toResponseBody("application/json".toMediaType())).build()
        }.build())
        .addConverterFactory(GsonConverterFactory.create()).build().create(SaayaApiService::class.java)
    private val repository = TrustedCircleRepository(api, FastApiTrustedContactJsonMapper, "owner")

    private fun invitation(status: String = "PENDING", id: String = contactId) = """
        {"_id":"$id","ownerId":"owner","name":"Friend","relation":"Family",
         "phone":"+91 98765 43210","initials":"F","createdAt":"2026-11-13T00:00:00Z",
         "invitedRecipientId":"recipient","invitationStatus":"$status",
         "invitedAt":"2026-11-13T00:01:00Z","invitationId":"$invitationId"}
    """.trimIndent()

    @Test fun sendsExactBackendRequestsAndDecodesInvitationMetadata() = runBlocking {
        response = invitation()
        val sent = repository.sendInvitation(contactId, saayaId)
        assertEquals(contactId, sent.id)
        assertEquals(invitationId, sent.invitationId)
        assertEquals("+91 98765 43210", sent.phone)
        assertEquals(InvitationStatus.PENDING, sent.invitationStatus)
        assertEquals(null, sent.decidedAt)
        response = """{"contacts":[${invitation()}]}"""
        assertEquals(listOf(sent), repository.loadIncomingInvitations())
        response = invitation("ACCEPTED")
        assertEquals(InvitationStatus.ACCEPTED, repository.acceptInvitation(contactId, invitationId).invitationStatus)
        response = invitation("REJECTED")
        assertEquals(InvitationStatus.REJECTED, repository.rejectInvitation(contactId, invitationId).invitationStatus)
        assertEquals(listOf("POST", "GET", "POST", "POST"), requests.map { it.method })
        assertEquals(listOf("/contacts/$contactId/invitation", "/contacts/invitations/incoming",
            "/contacts/$contactId/invitation/accept", "/contacts/$contactId/invitation/reject"),
            requests.map { it.url.encodedPath })
        requests.forEach { assertEquals(null, it.url.query) }
        val sendBody = JsonParser.parseString(bodies[0]).asJsonObject
        assertEquals(setOf("recipientSaayaId"), sendBody.keySet())
        assertEquals(saayaId, sendBody["recipientSaayaId"].asString)
        assertEquals("", bodies[1])
        for (index in listOf(2, 3)) {
            val decision = JsonParser.parseString(bodies[index]).asJsonObject
            assertEquals(setOf("invitationId"), decision.keySet())
            assertEquals(invitationId, decision["invitationId"].asString)
        }
    }

    @Test fun apiErrorsKeepStatusAndNeverBecomeSuccess() {
        val operations: List<suspend () -> Any> = listOf(
            { repository.sendInvitation(contactId, saayaId) },
            { repository.loadIncomingInvitations() },
            { repository.acceptInvitation(contactId, invitationId) },
            { repository.rejectInvitation(contactId, invitationId) }
        )
        for (status in listOf(401, 403, 404, 409, 422, 503)) {
            code = status
            response = """{"detail":"API failure"}"""
            operations.forEach { operation ->
                val failure = assertThrows(HttpException::class.java) { runBlocking { operation() } }
                assertEquals(status, failure.code())
                assertEquals(response, failure.response()!!.errorBody()!!.string())
            }
        }
    }

    @Test fun missingAndMalformedResponsesAreRejectedAndEmptyIncomingIsValid() {
        code = 204
        assertThrows(IllegalStateException::class.java) {
            runBlocking { repository.sendInvitation(contactId, saayaId) }
        }
        code = 200
        response = "{}"
        assertThrows(IllegalStateException::class.java) { runBlocking { repository.loadIncomingInvitations() } }
        assertThrows(IllegalStateException::class.java) { runBlocking { repository.sendInvitation(contactId, saayaId) } }
        response = invitation(id = "another-contact")
        assertThrows(IllegalStateException::class.java) { runBlocking { repository.sendInvitation(contactId, saayaId) } }
        response = invitation("UNKNOWN")
        assertThrows(IllegalStateException::class.java) { runBlocking { repository.sendInvitation(contactId, saayaId) } }
        response = """{"contacts":[${invitation("ACCEPTED")}]}"""
        assertThrows(IllegalStateException::class.java) { runBlocking { repository.loadIncomingInvitations() } }
        response = """{"contacts":[]}"""
        assertEquals(emptyList<ContactInvitationResponse>(), runBlocking { repository.loadIncomingInvitations() })
    }

    @Test fun invalidInputsFailBeforeNetworkAndNetworkFailuresPropagate() {
        assertThrows(IllegalArgumentException::class.java) { runBlocking { repository.sendInvitation(" ", saayaId) } }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { repository.sendInvitation(contactId, " ") } }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { repository.acceptInvitation(contactId, "short") } }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { repository.rejectInvitation("", invitationId) } }
        assertEquals(0, requests.size)
        networkFailure = true
        assertThrows(IOException::class.java) { runBlocking { repository.loadIncomingInvitations() } }
    }

    @Test fun createsContactThroughExistingPostBeforeItCanBeInvited() = runBlocking {
        response = """{"_id":"$contactId","ownerId":"owner","name":"Mum","relation":"Family","phone":"+91 98765 43210","initials":"M","createdAt":"2026-11-13T00:00:00Z"}"""
        val saved = repository.createInvitationContact(TrustedContact("Mum", "Family", "+91 98765 43210", "M"))
        assertEquals(contactId, saved.id)
        assertEquals("+91 98765 43210", saved.phone)
        assertEquals("/contacts", requests.single().url.encodedPath)
        val body = JsonParser.parseString(bodies.single()).asJsonObject
        assertEquals(setOf("ownerId", "name", "relation", "phone", "initials"), body.keySet())
        assertEquals("owner", body["ownerId"].asString)
    }
}
