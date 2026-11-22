package com.project.saaya.ui.state

import org.junit.Assert.*
import org.junit.Test

class TrustedCircleMemberFormTest {
    private val valid = TrustedCircleMemberForm("SAAYA-" + "A".repeat(24), "+91 98765 43210", "Ananya Sharma", "Sister")

    @Test fun sendRequiresSaayaIdAndValidEmergencyNumber() {
        assertTrue(valid.canSend)
        assertFalse(valid.copy(saayaId = "").canSend)
        assertFalse(valid.copy(saayaId = "SAAYA-" + "G".repeat(24)).canSend)
        assertFalse(valid.copy(phone = "").canSend)
        assertFalse(valid.copy(phone = "123*456#").canSend)
        assertFalse(valid.copy(phone = "1".repeat(33)).canSend)
        assertFalse(valid.copy(name = "a".repeat(101)).canSend)
    }

    @Test fun preservesEmergencyNumberAndSavedIdWhileNormalizingSaayaId() {
        val form = valid.copy(saayaId = "  saaya-" + "a".repeat(24) + "  ")
        assertTrue(form.canSend)
        assertEquals(valid.saayaId, form.normalizedSaayaId)
        val contact = form.contact("saved-id")
        assertEquals(valid.phone, contact.phone)
        assertEquals("saved-id", contact.id)
        assertEquals("AS", contact.initials)
        assertEquals("Sister", contact.relation)
    }

    @Test fun phoneOnlyContactsRemainAvailableAndOptionalLabelsHaveDefaults() {
        val form = TrustedCircleMemberForm(phone = "+919876543210")
        assertTrue(form.canSaveContact)
        assertFalse(form.canSend)
        assertEquals("Trusted member", form.contact().name)
        assertEquals("Trusted Circle", form.contact().relation)
        assertEquals("TM", form.contact().initials)
    }
}
