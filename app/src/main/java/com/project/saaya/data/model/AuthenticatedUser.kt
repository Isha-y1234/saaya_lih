package com.project.saaya.data.model

import com.google.gson.annotations.SerializedName

data class AuthenticatedUser(
    @SerializedName("_id") val id: String,
    val name: String,
    val email: String,
    val saayaId: String? = null
)

/** Stored only in encrypted, non-backed-up storage; never in Compose saved state. */
class AuthSession(val token: String, val user: AuthenticatedUser, val expiresAt: Long) {
    override fun toString() = "AuthSession(userId=${user.id}, expiresAt=$expiresAt)"
}
