package com.project.saaya.notifications

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.project.saaya.MainActivity
import com.project.saaya.R

object SosNotifications {
    const val CHANNEL = "saaya_sos_v2"
    const val ID = 1001

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = context.getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(CHANNEL) == null) {
                val channel = NotificationChannel(CHANNEL, "Saaya Emergency SOS", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Critical alerts for Trusted Circle SOS incidents"
                    enableLights(true)
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 500, 200, 500, 200, 500)
                    setBypassDnd(true)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC

                    val audioAttributes = AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .build()
                    setSound(Settings.System.DEFAULT_RINGTONE_URI, audioAttributes)
                }
                manager.createNotificationChannel(channel)
            }
        }
    }

    fun show(context: Context, payload: SosNotificationPayload) {
        ensureChannel(context)
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context,
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        if (!isEnabled(context)) return

        val fullScreenIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION
            data = Uri.parse("saaya://sos/${payload.incidentId}")
            putExtra("type", SosNotificationPayload.TYPE)
            putExtra("incidentId", payload.incidentId)
            putExtra("recipientId", payload.recipientId)
        }
        val fullScreenPendingIntent = PendingIntent.getActivity(
            context, 0, fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val joinIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            data = Uri.parse("saaya://sos/${payload.incidentId}/live")
            putExtra("type", SosNotificationPayload.TYPE)
            putExtra("incidentId", payload.incidentId)
            putExtra("recipientId", payload.recipientId)
            putExtra("action", "join_live")
        }
        val joinPendingIntent = PendingIntent.getActivity(
            context, 1, joinIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val dismissIntent = Intent(context, SosActionReceiver::class.java).apply {
            action = SosActionReceiver.ACTION_DISMISS
            putExtra(SosActionReceiver.EXTRA_INCIDENT_ID, payload.incidentId)
        }
        val dismissPendingIntent = PendingIntent.getBroadcast(
            context, 2, dismissIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_sos_notification)
            .setContentTitle(payload.title)
            .setContentText(payload.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(payload.body))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .setContentIntent(fullScreenPendingIntent)
            .addAction(0, "Join Live", joinPendingIntent)
            .addAction(0, "Dismiss", dismissPendingIntent)
            .setAutoCancel(true)
            .setOngoing(true)
            .setVibrate(longArrayOf(0, 500, 200, 500, 200, 500))
            .setSound(Settings.System.DEFAULT_RINGTONE_URI)
            .build()

        NotificationManagerCompat.from(context).notify("saaya-sos:${payload.incidentId}", ID, notification)
    }

    fun isEnabled(context: Context): Boolean {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        return Build.VERSION.SDK_INT < 26 || context.getSystemService(NotificationManager::class.java)
            .getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
    }

    fun clear(context: Context, incidentId: String? = null) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (incidentId != null) {
            manager.cancel("saaya-sos:$incidentId", ID)
        } else {
            manager.activeNotifications.filter { it.tag?.startsWith("saaya-sos:") == true }
                .forEach { manager.cancel(it.tag, it.id) }
        }
    }
}
