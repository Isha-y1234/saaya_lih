package com.project.saaya.ui.screens

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.project.saaya.MainActivity

class JourneyCheckInReceiver : BroadcastReceiver() {

    companion object {

        const val ACTION_CHECK_IN =
            "com.project.saaya.ACTION_JOURNEY_CHECK_IN"

        const val ACTION_SAFE =
            "com.project.saaya.ACTION_JOURNEY_SAFE"

        const val ACTION_NEED_HELP =
            "com.project.saaya.ACTION_JOURNEY_NEED_HELP"

        private const val TAG =
            "SAAYA_CHECKIN"

        private const val CHANNEL_ID =
            "journey_check_ins"

        private const val NOTIFICATION_ID =
            4202
    }

    override fun onReceive(
        context: Context,
        intent: Intent
    ) {

        Log.d(
            TAG,
            "Receiver received action: ${intent.action}"
        )

        when (intent.action) {

            ACTION_CHECK_IN -> {
                showCheckInNotification(context)
            }

            ACTION_SAFE -> {
                handleSafe(context)
            }

            ACTION_NEED_HELP -> {
                handleNeedHelp(context)
            }

            else -> {
                Log.w(
                    TAG,
                    "Unknown receiver action: ${intent.action}"
                )
            }
        }
    }

    private fun handleSafe(
        context: Context
    ) {

        Log.d(
            TAG,
            "I'M SAFE button clicked"
        )

        /*
         * Remove the check-in notification.
         */
        NotificationManagerCompat
            .from(context)
            .cancel(NOTIFICATION_ID)

        /*
         * Store the successful check-in locally.
         * We can later display this in Journey History.
         */
        context
            .getSharedPreferences(
                "saaya_journey",
                Context.MODE_PRIVATE
            )
            .edit()
            .putBoolean(
                "last_checkin_completed",
                true
            )
            .putLong(
                "last_checkin_time",
                System.currentTimeMillis()
            )
            .apply()

        Log.d(
            TAG,
            "Check-in completed successfully."
        )
    }

    private fun handleNeedHelp(
        context: Context
    ) {

        Log.d(
            TAG,
            "NEED HELP button clicked"
        )

        /*
         * Remove the check-in notification.
         */
        NotificationManagerCompat
            .from(context)
            .cancel(NOTIFICATION_ID)

        /*
         * Open Saaya.
         *
         * MainActivity can use OPEN_HELP_FLOW later to
         * navigate directly into the emergency flow.
         */
        val helpIntent =
            Intent(
                context,
                MainActivity::class.java
            ).apply {

                flags =
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP

                putExtra(
                    "OPEN_HELP_FLOW",
                    true
                )
            }

        try {

            context.startActivity(
                helpIntent
            )

            Log.d(
                TAG,
                "Opened MainActivity for help."
            )

        } catch (exception: Exception) {

            Log.e(
                TAG,
                "Could not open MainActivity",
                exception
            )

            /*
             * If MainActivity cannot be opened,
             * show a fallback notification.
             */
            showHelpFallbackNotification(context)
        }
    }

    private fun showCheckInNotification(
        context: Context
    ) {

        createNotificationChannel(context)

        if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.TIRAMISU
        ) {

            val permissionGranted =
                context.checkSelfPermission(
                    Manifest.permission.POST_NOTIFICATIONS
                ) ==
                        PackageManager.PERMISSION_GRANTED

            if (!permissionGranted) {

                Log.e(
                    TAG,
                    "Notification permission is not granted."
                )

                return
            }
        }

        /*
         * I'M SAFE
         */
        val safeIntent =
            Intent(
                context,
                JourneyCheckInReceiver::class.java
            ).apply {
                action = ACTION_SAFE
            }

        val safePendingIntent =
            PendingIntent.getBroadcast(
                context,
                4203,
                safeIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or
                        PendingIntent.FLAG_IMMUTABLE
            )

        /*
         * NEED HELP
         */
        val helpIntent =
            Intent(
                context,
                JourneyCheckInReceiver::class.java
            ).apply {
                action = ACTION_NEED_HELP
            }

        val helpPendingIntent =
            PendingIntent.getBroadcast(
                context,
                4204,
                helpIntent,
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
                            "You're still on your protected journey. " +
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
                .addAction(
                    NotificationCompat.Action.Builder(
                        0,
                        "I'm Safe",
                        safePendingIntent
                    ).build()
                )
                .addAction(
                    NotificationCompat.Action.Builder(
                        0,
                        "Need Help",
                        helpPendingIntent
                    ).build()
                )
                .build()

        NotificationManagerCompat
            .from(context)
            .notify(
                NOTIFICATION_ID,
                notification
            )

        Log.d(
            TAG,
            "Check-in notification posted with actions."
        )
    }

    private fun showHelpFallbackNotification(
        context: Context
    ) {

        createNotificationChannel(context)

        if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val notification =
            NotificationCompat.Builder(
                context,
                CHANNEL_ID
            )
                .setSmallIcon(
                    android.R.drawable.ic_dialog_alert
                )
                .setContentTitle(
                    "Saaya • Help requested"
                )
                .setContentText(
                    "Open Saaya to access your emergency options."
                )
                .setPriority(
                    NotificationCompat.PRIORITY_MAX
                )
                .setCategory(
                    NotificationCompat.CATEGORY_ALARM
                )
                .setAutoCancel(true)
                .build()

        NotificationManagerCompat
            .from(context)
            .notify(
                NOTIFICATION_ID,
                notification
            )
    }

    private fun createNotificationChannel(
        context: Context
    ) {

        if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.O
        ) {

            val channel =
                NotificationChannel(
                    CHANNEL_ID,
                    "Journey Check-ins",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {

                    description =
                        "Safety check-ins during protected journeys"

                    enableVibration(true)
                }

            val manager =
                context.getSystemService(
                    Context.NOTIFICATION_SERVICE
                ) as NotificationManager

            manager.createNotificationChannel(
                channel
            )
        }
    }
}
