package com.project.saaya.notifications

import com.project.saaya.data.model.AuthSession
import com.project.saaya.data.model.AuthenticatedUser
import com.project.saaya.data.remote.ContactInvitationResponse
import com.project.saaya.data.remote.InvitationStatus
import org.junit.Assert.*
import org.junit.Test

class InvitationNotificationPayloadTest {
    private val id = "aB_-".repeat(8)
    private val user = "507f1f77bcf86cd799439011"
    private val other = "507f1f77bcf86cd799439012"
    private val data = mapOf("type" to InvitationNotificationPayload.TYPE, "invitationId" to id)
    private val payload = InvitationNotificationPayload.parse(data)!!
    private val invitation = ContactInvitationResponse("contact", other, "Friend", "Family", "123", "F",
        "2026-11-13T00:00:00Z", user, InvitationStatus.PENDING, "2026-11-13T00:00:00Z", id)
    private val session = AuthSession("session", AuthenticatedUser(user, "User", "user@example.com"), 2000)

    @Test fun onlyInvitationTypeAndBoundedUrlSafeIdAreAccepted() {
        assertEquals(id, payload.invitationId)
        listOf(emptyMap(), data - "invitationId", data + ("type" to "SOS"), data + ("type" to "OTHER"),
            data + ("invitationId" to "x".repeat(31)), data + ("invitationId" to "x".repeat(33)),
            data + ("invitationId" to ("x".repeat(31) + "/")), data + ("invitationId" to "../../incidents"))
            .forEach { assertNull(InvitationNotificationPayload.parse(it)) }
    }

    @Test fun invitationMessagesNeverBecomeSosPayloadsEvenWithIncidentFields() {
        assertNull(SosNotificationPayload.parse(data))
        assertNull(SosNotificationPayload.parse(data + mapOf("incidentId" to user, "recipientId" to user)))
        assertNull(InvitationNotificationPayload.parse(mapOf("type" to "SOS", "incidentId" to user, "recipientId" to user)))
    }

    @Test fun onlyAuthenticatedPendingInvitationForCurrentAccountCanNotify() {
        assertTrue(payload.isPendingFor(user, listOf(invitation)))
        assertFalse(payload.isPendingFor(other, listOf(invitation)))
        assertFalse(payload.isPendingFor(user, emptyList()))
        assertFalse(payload.isPendingFor(user, listOf(invitation.copy(ownerId = user))))
        assertFalse(payload.isPendingFor(user, listOf(invitation.copy(invitationId = "different"))))
        for (status in listOf(InvitationStatus.ACCEPTED, InvitationStatus.REJECTED, InvitationStatus.REVOKED)) {
            assertFalse(payload.isPendingFor(user, listOf(invitation.copy(invitationStatus = status))))
        }
    }

    @Test fun accountSwitchLogoutExpiryAndSessionReplacementDropPendingNotification() {
        assertNotNull(payload.verifiedTargetFor(session, session, listOf(invitation), 1000))
        assertNull(payload.verifiedTargetFor(session, null, listOf(invitation), 1000))
        assertNull(payload.verifiedTargetFor(session, AuthSession("new-session", session.user, session.expiresAt), listOf(invitation), 1000))
        assertNull(payload.verifiedTargetFor(session, AuthSession(session.token, session.user.copy(id = other), session.expiresAt), listOf(invitation), 1000))
        assertNull(payload.verifiedTargetFor(session, session, listOf(invitation), 2000))
        assertNull(payload.verifiedTargetFor(session, session, emptyList(), 1000))
    }

    @Test fun savedTapRestoresOnlyTrustedCircleForBoundAccountAfterOnboarding() {
        val extras = data + ("invitationAccountId" to user)
        val target = InvitationNotificationTarget.parse(extras)!!
        assertEquals(InvitationNotificationTarget(id, user), target)
        assertEquals("trusted_circle", target.destinationFor(user, true))
        assertNull(target.destinationFor(null, true))
        assertNull(target.destinationFor(other, true))
        assertNull(target.destinationFor(user, false))
        assertNull(InvitationNotificationTarget.parse(data))
        assertNull(InvitationNotificationTarget.parse(extras + ("invitationAccountId" to "bad")))
        assertNull(InvitationNotificationTarget.parse(extras + ("type" to "SOS")))
    }

    @Test fun unrelatedPayloadRoutingAndSensitiveFieldsAreNeverUsed() {
        val target = InvitationNotificationTarget.parse(data + mapOf("invitationAccountId" to user,
            "route" to "live_safety/$user", "incidentId" to user, "title" to "SOS", "phone" to "123"))!!
        assertEquals("trusted_circle", target.destinationFor(user, true))
        assertEquals(InvitationNotificationTarget(id, user), target)
    }

    @Test fun backgroundApiUsesActiveSnapshotAndCannotClearRealAuthentication() {
        val manager = invitationSessionManager(session)
        assertEquals(session, manager.current)
        assertEquals(session, manager.saved())
        manager.invalidate(session.token)
        assertNull(manager.current)
        assertNull(manager.saved())
        assertEquals("session", session.token)
    }
}
