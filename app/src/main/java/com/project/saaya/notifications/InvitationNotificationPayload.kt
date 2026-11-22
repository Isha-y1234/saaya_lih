package com.project.saaya.notifications

import com.project.saaya.data.remote.ContactInvitationResponse
import com.project.saaya.data.remote.InvitationStatus
import com.project.saaya.data.model.AuthSession

/** A routing hint, never an authorization grant or an incident identifier. */
data class InvitationNotificationPayload(val invitationId: String) {
    fun isPendingFor(userId: String, incoming: List<ContactInvitationResponse>): Boolean = incoming.any {
        it.invitationId == invitationId && it.invitedRecipientId == userId && it.ownerId != userId &&
            it.invitationStatus == InvitationStatus.PENDING
    }

    fun verifiedTargetFor(expected: AuthSession, current: AuthSession?, incoming: List<ContactInvitationResponse>,
                          nowSeconds: Long): InvitationNotificationTarget? {
        if (current == null || expected.expiresAt <= nowSeconds || current.expiresAt <= nowSeconds ||
            expected.token != current.token || expected.user.id != current.user.id ||
            !isPendingFor(expected.user.id, incoming)) return null
        return InvitationNotificationTarget(invitationId, expected.user.id)
    }

    companion object {
        const val TYPE = "TRUSTED_CIRCLE_INVITATION"
        private val idPattern = Regex("^[A-Za-z0-9_-]{32}$")
        fun parse(data: Map<String, String>): InvitationNotificationPayload? {
            if (data["type"] != TYPE) return null
            val id = data["invitationId"] ?: return null
            return if (idPattern.matches(id)) InvitationNotificationPayload(id) else null
        }
    }
}

/** Locally bound after an authenticated API check; the push itself has no account ID. */
data class InvitationNotificationTarget(val invitationId: String, val accountId: String) {
    fun belongsTo(userId: String?) = accountId == userId
    fun destinationFor(userId: String?, onboardingComplete: Boolean): String? =
        if (belongsTo(userId) && onboardingComplete) "trusted_circle" else null

    companion object {
        fun parse(data: Map<String, String>): InvitationNotificationTarget? {
            val payload = InvitationNotificationPayload.parse(data) ?: return null
            val account = data["invitationAccountId"] ?: return null
            if (!Regex("^[a-fA-F0-9]{24}$").matches(account)) return null
            return InvitationNotificationTarget(payload.invitationId, account)
        }
    }
}
