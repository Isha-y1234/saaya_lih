package com.project.saaya.notifications

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.project.saaya.data.local.EncryptedAuthSessionStore

class SaayaMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        // Fetch the current SDK token in persistent background work; never log or put tokens in WorkManager input data.
        FcmTokenSync.enqueue(applicationContext)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        if (message.data["type"] == InvitationNotificationPayload.TYPE) {
            InvitationNotificationPayload.parse(message.data)?.let {
                InvitationNotificationWork.enqueue(applicationContext, it)
            }
            return
        }
        val data = message.data.toMutableMap()
        message.notification?.title?.let { data.putIfAbsent("title", it) }
        message.notification?.body?.let { data.putIfAbsent("body", it) }
        val payload = SosNotificationPayload.parse(data) ?: return
        val session = EncryptedAuthSessionStore(applicationContext).read() ?: return
        if (session.expiresAt <= System.currentTimeMillis() / 1000 || !payload.belongsTo(session.user.id)) return
        SosNotifications.show(applicationContext, payload)
    }
}
