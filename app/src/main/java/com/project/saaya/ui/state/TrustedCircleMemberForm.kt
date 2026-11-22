package com.project.saaya.ui.state

import com.project.saaya.data.model.TrustedContact
import java.util.Locale

/** Shared by the form and local validation tests; sending never changes a saved phone. */
data class TrustedCircleMemberForm(
    val saayaId: String = "",
    val phone: String = "",
    val name: String = "",
    val relation: String = ""
) {
    val normalizedSaayaId: String get() = saayaId.trim().uppercase(Locale.ROOT)
    val canSaveContact: Boolean get() = phone.trim().length in 3..32 &&
        Regex("^\\+?[0-9][0-9 ()-]*$").matches(phone.trim()) && phone.count { it.isDigit() } >= 3 &&
        name.trim().length <= 100 && relation.trim().length <= 100
    val canSend: Boolean get() = canSaveContact && Regex("^SAAYA-[0-9A-F]{24}$").matches(normalizedSaayaId)

    fun contact(id: String = ""): TrustedContact {
        val displayName = name.trim().ifBlank { "Trusted member" }
        val initials = displayName.split(Regex("\\s+")).take(2).joinToString("") { it.take(1) }.uppercase(Locale.ROOT)
        return TrustedContact(displayName, relation.trim().ifBlank { "Trusted Circle" }, phone.trim(), initials, id)
    }
}
