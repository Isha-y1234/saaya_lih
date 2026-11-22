package com.project.saaya.ui.screens

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.project.saaya.MainActivity

object SaayaNotificationHelper {

    const val CHANNEL_ID = "saaya_safety_checkins"
    const val NOTIFICATION_ID = 9001

    fun createNotificationChannel(
        context: Context
    ) {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

            val channel = NotificationChannel(
                CHANNEL_ID,
                "Journey Safety Check-ins",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {

                description =
                    "Safety check-ins during active Saaya journeys"

                enableVibration(true)
            }

            val notificationManager =
                context.getSystemService(
                    Context.NOTIFICATION_SERVICE
                ) as NotificationManager

            notificationManager.createNotificationChannel(
                channel
            )
        }
    }

    fun showCheckInNotification(
        context: Context
    ) {

        createNotificationChannel(context)

        /*
         * I'M SAFE action
         */
        val safeIntent = Intent(
            context,
            JourneyCheckInReceiver::class.java
        ).apply {
            action = JourneyCheckInReceiver.ACTION_SAFE
        }

        val safePendingIntent = PendingIntent.getBroadcast(
            context,
            9002,
            safeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or
                    PendingIntent.FLAG_IMMUTABLE
        )

        /*
         * I NEED HELP action
         */
        val helpIntent = Intent(
            context,
            JourneyCheckInReceiver::class.java
        ).apply {
            action = JourneyCheckInReceiver.ACTION_NEED_HELP
        }

        val helpPendingIntent = PendingIntent.getBroadcast(
            context,
            9003,
            helpIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or
                    PendingIntent.FLAG_IMMUTABLE
        )

        /*
         * Open Saaya if the notification body is tapped.
         */
        val openAppIntent = Intent(
            context,
            MainActivity::class.java
        ).apply {
            flags =
                Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val openAppPendingIntent =
            PendingIntent.getActivity(
                context,
                9004,
                openAppIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or
                        PendingIntent.FLAG_IMMUTABLE
            )

        val notification =
            NotificationCompat.Builder(
                context,
                CHANNEL_ID
            )
                .setSmallIcon(
                    android.R.drawable.ic_dialog_info
                )
                .setContentTitle(
                    "Saaya • Are you okay?"
                )
                .setContentText(
                    "Your journey is still active."
                )
                .setStyle(
                    NotificationCompat.BigTextStyle()
                        .bigText(
                            "Your journey is still active. " +
                                    "Please confirm that you're safe."
                        )
                )
                .setPriority(
                    NotificationCompat.PRIORITY_HIGH
                )
                .setCategory(
                    NotificationCompat.CATEGORY_REMINDER
                )
                .setAutoCancel(false)
                .setOngoing(true)
                .setContentIntent(
                    openAppPendingIntent
                )
                .addAction(
                    android.R.drawable.ic_menu_save,
                    "I'm Safe",
                    safePendingIntent
                )
                .addAction(
                    android.R.drawable.ic_dialog_alert,
                    "I Need Help",
                    helpPendingIntent
                )
                .build()

        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        if (
            Build.VERSION.SDK_INT < 33 ||
            NotificationManagerCompat
                .from(context)
                .areNotificationsEnabled()
        ) {

            NotificationManagerCompat
                .from(context)
                .notify(
                    NOTIFICATION_ID,
                    notification
                )
        }
    }

    fun cancelCheckInNotification(
        context: Context
    ) {

        NotificationManagerCompat
            .from(context)
            .cancel(
                NOTIFICATION_ID
            )
    }
}