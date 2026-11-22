package com.project.saaya.data.repository

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.project.saaya.data.model.TrustedContact
import com.project.saaya.data.remote.SaayaApiService
import com.project.saaya.data.remote.ContactInvitationRequest
import com.project.saaya.data.remote.ContactInvitationDecisionRequest
import com.project.saaya.data.remote.ContactInvitationResponse
import com.project.saaya.data.remote.InvitationStatus
import retrofit2.HttpException
import retrofit2.Response

/** Maps the FastAPI JSON contract to the existing UI/domain model. */
interface TrustedContactJsonMapper {
    fun decodeContacts(payload: JsonElement): List<TrustedContact>
    fun encodeNewContact(contact: TrustedContact, ownerId: String): JsonElement
}

object FastApiTrustedContactJsonMapper : TrustedContactJsonMapper {
    override fun decodeContacts(payload: JsonElement): List<TrustedContact> {
        val contacts = payload.asJsonObject.get("contacts")
        require(contacts != null && contacts.isJsonArray) { "Invalid contacts response" }
        return contacts.asJsonArray.map { element ->
            val contact = element.asJsonObject
            TrustedContact(
                name = contact.requiredString("name"),
                relation = contact.requiredString("relation"),
                phone = contact.requiredString("phone"),
                initials = contact.requiredString("initials"),
                id = contact.requiredString("_id"),
                invitationStatus = contact.get("invitationStatus")?.takeUnless { it.isJsonNull }?.let {
                    InvitationStatus.valueOf(it.asString)
                }
            )
        }
    }

    override fun encodeNewContact(contact: TrustedContact, ownerId: String): JsonElement =
        JsonObject().apply {
            addProperty("ownerId", ownerId)
            addProperty("name", contact.name)
            addProperty("relation", contact.relation)
            addProperty("phone", contact.phone)
            addProperty("initials", contact.initials)
        }

    private fun JsonObject.requiredString(field: String): String {
        val value = get(field)
        require(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isString) {
            "Invalid contact field: $field"
        }
        return value.asString.also { require(it.isNotBlank()) { "Empty contact field: $field" } }
    }
}

class TrustedCircleRepository(
    private val api: SaayaApiService,
    private val mapper: TrustedContactJsonMapper,
    private val ownerId: String
) {
    init {
        require(ownerId.isNotBlank()) { "Owner ID must not be blank" }
    }

    suspend fun loadContacts(): List<TrustedContact> {
        val response = api.getContacts(ownerId)
        response.requireSuccess()
        val payload = response.body()
            ?: error("Contacts response has no JSON body")
        return mapper.decodeContacts(payload)
    }

    suspend fun addContact(contact: TrustedContact) {
        api.addContact(mapper.encodeNewContact(contact, ownerId)).requireSuccess()
    }

    suspend fun createInvitationContact(contact: TrustedContact): TrustedContact {
        val saved = api.createInvitationContact(mapper.encodeNewContact(contact, ownerId)).bodyOrThrow()
        val envelope = JsonObject().apply {
            add("contacts", com.google.gson.JsonArray().apply { add(saved) })
        }
        return mapper.decodeContacts(envelope).single()
    }

    /** Use the backend _id, never a name or phone number. */
    suspend fun deleteContact(id: String) {
        require(id.isNotBlank()) { "Contact ID must not be blank" }
        api.deleteContact(id).requireSuccess()
    }

    /** Invite an account through an already-saved contact; its emergency phone is untouched. */
    suspend fun sendInvitation(contactId: String, recipientSaayaId: String): ContactInvitationResponse {
        require(contactId.isNotBlank()) { "Contact ID must not be blank" }
        require(recipientSaayaId.isNotBlank()) { "Recipient Saaya ID must not be blank" }
        return api.sendInvitation(contactId, ContactInvitationRequest(recipientSaayaId.trim()))
            .invitationBody(contactId, InvitationStatus.PENDING)
    }

    suspend fun loadIncomingInvitations(): List<ContactInvitationResponse> {
        val body = api.getIncomingInvitations().bodyOrThrow()
        // Gson can leave required Kotlin fields null for malformed JSON.
        val contacts = checkNotNull(body.contacts) { "Missing incoming invitations" }
        return contacts.map { it.validated(InvitationStatus.PENDING) }
    }

    suspend fun acceptInvitation(contactId: String, invitationId: String): ContactInvitationResponse {
        validateDecision(contactId, invitationId)
        return api.acceptInvitation(contactId, ContactInvitationDecisionRequest(invitationId))
            .invitationBody(contactId, InvitationStatus.ACCEPTED)
    }

    suspend fun rejectInvitation(contactId: String, invitationId: String): ContactInvitationResponse {
        validateDecision(contactId, invitationId)
        return api.rejectInvitation(contactId, ContactInvitationDecisionRequest(invitationId))
            .invitationBody(contactId, InvitationStatus.REJECTED)
    }

    private fun validateDecision(contactId: String, invitationId: String) {
        require(contactId.isNotBlank()) { "Contact ID must not be blank" }
        require(invitationId.length == 32) { "Invitation ID must contain 32 characters" }
    }

    private fun Response<ContactInvitationResponse>.invitationBody(
        contactId: String, status: InvitationStatus
    ): ContactInvitationResponse = bodyOrThrow().validated(status).also {
        check(it.id == contactId) { "Unexpected invitation contact ID" }
    }

    private fun ContactInvitationResponse.validated(status: InvitationStatus): ContactInvitationResponse = also {
        check(listOf(id, ownerId, name, relation, phone, initials, createdAt, invitedRecipientId, invitedAt)
            .all { !it.isNullOrBlank() } && invitationId?.length == 32 && invitationStatus == status) {
            "Invalid invitation response"
        }
    }

    private fun <T> Response<T>.bodyOrThrow(): T {
        requireSuccess()
        return body() ?: error("Invitation response has no JSON body")
    }

    private fun Response<*>.requireSuccess() {
        if (!isSuccessful) throw HttpException(this)
    }
}
