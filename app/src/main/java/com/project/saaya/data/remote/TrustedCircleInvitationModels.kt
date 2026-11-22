package com.project.saaya.data.remote

import com.google.gson.annotations.SerializedName

data class ContactInvitationRequest(val recipientSaayaId: String)

data class ContactInvitationDecisionRequest(val invitationId: String)

enum class InvitationStatus { PENDING, ACCEPTED, REJECTED, REVOKED }

/** The backend ContactResponse, including its optional invitation metadata. */
data class ContactInvitationResponse(
    @SerializedName("_id") val id: String,
    val ownerId: String,
    val name: String,
    val relation: String,
    val phone: String,
    val initials: String,
    val createdAt: String,
    val invitedRecipientId: String? = null,
    val invitationStatus: InvitationStatus? = null,
    val invitedAt: String? = null,
    val invitationId: String? = null,
    val decidedAt: String? = null
)

data class IncomingInvitationsResponse(val contacts: List<ContactInvitationResponse>)
