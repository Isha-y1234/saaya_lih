package com.project.saaya.data.repository

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.project.saaya.data.model.TrustedContact
import com.project.saaya.data.remote.SaayaApiService
import com.project.saaya.data.remote.ContactInvitationRequest
import com.project.saaya.data.remote.ContactInvitationDecisionRequest
import com.project.saaya.data.remote.ContactInvitationResponse
import com.project.saaya.data.remote.IncomingInvitationsResponse
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import okhttp3.ResponseBody.Companion.toResponseBody

class TrustedCircleRepositoryTest {
    private val contact = TrustedContact("Ananya Sharma", "Sister", "+911234567890", "AS")
    private val payload = JsonObject()
    private val api = FakeApi()
    private var decoded: JsonElement? = null
    private val mapper = object : TrustedContactJsonMapper {
        override fun decodeContacts(payload: JsonElement): List<TrustedContact> {
            decoded = payload
            return listOf(contact)
        }

        override fun encodeNewContact(contact: TrustedContact, ownerId: String): JsonElement {
            assertEquals(this@TrustedCircleRepositoryTest.contact, contact)
            assertEquals("test-user-001", ownerId)
            return payload
        }
    }
    private val repository = TrustedCircleRepository(api, mapper, "test-user-001")

    @Test
    fun loadsViaExplicitMapperWithoutAssumingJsonFields() = runBlocking {
        api.contactsResponse = Response.success(payload)
        assertEquals(listOf(contact), repository.loadContacts())
        assertSame(payload, decoded)
        assertEquals("test-user-001", api.loadedOwnerId)
    }

    @Test
    fun sendsMappedPayloadAndBackendIdAndAcceptsEmptyMutationResponses() = runBlocking {
        repository.addContact(contact)
        repository.deleteContact("backend-id")
        assertSame(payload, api.addedPayload)
        assertEquals("backend-id", api.deletedId)
    }

    @Test
    fun httpErrorsAreNotTreatedAsSuccess() {
        api.contactsResponse = Response.error(503, "Unavailable".toResponseBody())
        assertThrows(HttpException::class.java) { runBlocking { repository.loadContacts() } }
        assertEquals(null, decoded)
        api.mutationResponse = Response.error(403, "Forbidden".toResponseBody())
        assertThrows(HttpException::class.java) { runBlocking { repository.addContact(contact) } }
        assertThrows(HttpException::class.java) { runBlocking { repository.deleteContact("id") } }
    }

    @Test
    fun blankIdsAndMissingContactsBodiesFail() {
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repository.deleteContact(" ") }
        }
        assertEquals(null, api.deletedId)
        assertThrows(IllegalStateException::class.java) { runBlocking { repository.loadContacts() } }
    }

    private class FakeApi : SaayaApiService {
        override suspend fun createInvitationContact(payload: JsonElement): Response<JsonElement> = error("Unused creation API")

        override suspend fun sendInvitation(contactId: String, body: ContactInvitationRequest): Response<ContactInvitationResponse> =
            error("Unused invitation API")
        override suspend fun getIncomingInvitations(): Response<IncomingInvitationsResponse> = error("Unused invitation API")
        override suspend fun acceptInvitation(contactId: String, body: ContactInvitationDecisionRequest): Response<ContactInvitationResponse> =
            error("Unused invitation API")
        override suspend fun rejectInvitation(contactId: String, body: ContactInvitationDecisionRequest): Response<ContactInvitationResponse> =
            error("Unused invitation API")

        var contactsResponse: Response<JsonElement> = Response.success(null)
        var mutationResponse: Response<Unit> = Response.success<Unit>(204, null)
        var addedPayload: JsonElement? = null
        var deletedId: String? = null
        var loadedOwnerId: String? = null

        override suspend fun getContacts(ownerId: String): Response<JsonElement> {
            loadedOwnerId = ownerId
            return contactsResponse
        }
        override suspend fun addContact(payload: JsonElement): Response<Unit> {
            addedPayload = payload
            return mutationResponse
        }
        override suspend fun deleteContact(id: String): Response<Unit> {
            deletedId = id
            return mutationResponse
        }
    }
}
