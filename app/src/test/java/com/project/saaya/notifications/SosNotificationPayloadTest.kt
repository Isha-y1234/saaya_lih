package com.project.saaya.notifications

import org.junit.Assert.*
import org.junit.Test

class SosNotificationPayloadTest {
    private val id = "507f1f77bcf86cd799439011"
    private val recipient = "507f191e810c19729de860ea"
    private val data = mapOf("type" to "SOS", "incidentId" to id, "recipientId" to recipient)

    @Test fun validPayloadOpensExactIncidentOnlyForNotifiedAuthenticatedAccount() {
        val payload = SosNotificationPayload.parse(data)!!
        assertEquals("incident_alert/$id", payload.destinationFor(recipient, true))
        assertNull(payload.destinationFor(null, true))
        assertNull(payload.destinationFor("another-account", true))
        assertNull(payload.destinationFor(recipient, false))
    }

    @Test fun malformedOrUnrelatedPayloadsNeverCreateNavigationTargets() {
        listOf(emptyMap(), data - "recipientId", data - "incidentId", data + ("type" to "CHAT"),
            data + ("incidentId" to "../../users/me"), data + ("recipientId" to "bad-id"),
            data + ("incidentId" to "https://evil.example/")).forEach { assertNull(SosNotificationPayload.parse(it)) }
    }

    @Test fun titleAndBodyAreBoundedAndNotificationOnlyPayloadCannotBypassDataChecks() {
        val payload = SosNotificationPayload.parse(data + mapOf("title" to "x".repeat(500), "body" to "\n" + "y".repeat(500)))!!
        assertEquals(100, payload.title.length)
        assertEquals(300, payload.body.length)
        assertFalse(payload.body.contains('\n'))
        assertNull(SosNotificationPayload.parse(mapOf("title" to "SOS", "body" to "Tap here")))
    }
}
