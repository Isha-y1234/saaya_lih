package com.project.saaya.data.model

import com.project.saaya.data.remote.InvitationStatus

/** Backend ID is empty only for a new contact that has not been saved yet. */
data class TrustedContact(
    val name: String,
    val relation: String,
    val phone: String,
    val initials: String,
    val id: String = "",
    val invitationStatus: InvitationStatus? = null
)
