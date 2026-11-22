package com.project.saaya.notifications

data class SosNotificationPayload(
    val incidentId: String,
    val recipientId: String,
    val title: String,
    val body: String,
    val action: String? = null
) {
    fun belongsTo(userId: String?) = recipientId == userId
    fun destinationFor(userId: String?, onboardingComplete: Boolean): String? {
        if (!belongsTo(userId) || !onboardingComplete) return null
        return if (action == "join_live") "live_safety/$incidentId" else "incident_alert/$incidentId"
    }

    companion object {
        const val TYPE = "SOS"
        private val idPattern = Regex("^[a-fA-F0-9]{24}$")
        fun parse(data: Map<String, String>): SosNotificationPayload? {
            if (data["type"] != TYPE) return null
            val incidentId = data["incidentId"] ?: return null
            val recipientId = data["recipientId"] ?: return null
            if (!idPattern.matches(incidentId) || !idPattern.matches(recipientId)) return null
            fun clean(value: String?, fallback: String, limit: Int) = value?.filter { !it.isISOControl() }
                ?.trim()?.take(limit)?.takeIf { it.isNotEmpty() } ?: fallback
            return SosNotificationPayload(incidentId, recipientId,
                clean(data["title"], "Saaya SOS", 100),
                clean(data["body"], "A Trusted Circle contact needs help. Tap to view their incident.", 300),
                data["action"])
        }
    }
}
