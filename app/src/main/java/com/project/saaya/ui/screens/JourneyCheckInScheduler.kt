package com.project.saaya.ui.screens

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log

object JourneyCheckInScheduler {

    private const val TAG = "SAAYA_CHECKIN"

    private const val REQUEST_CODE = 4201


    fun scheduleMidJourneyCheckIn(
        context: Context,
        etaSeconds: Long
    ) {
        cancelMidJourneyCheckIn(context)

        // Real journey behavior: check in around the midpoint.
        // A minimum of 60 seconds prevents an immediate notification
        // on very short journeys.
        val delaySeconds =
            (etaSeconds / 2L).coerceAtLeast(60L)

        val triggerAtMillis =
            System.currentTimeMillis() +
                    delaySeconds * 1000L

        val intent =
            Intent(
                context,
                JourneyCheckInReceiver::class.java
            ).apply {
                action =
                    JourneyCheckInReceiver.ACTION_CHECK_IN
            }

        val pendingIntent =
            PendingIntent.getBroadcast(
                context,
                REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or
                        PendingIntent.FLAG_IMMUTABLE
            )

        val alarmManager =
            context.getSystemService(
                Context.ALARM_SERVICE
            ) as AlarmManager

        alarmManager.setAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            triggerAtMillis,
            pendingIntent
        )

        Log.d(
            TAG,
            "Check-in scheduled. " +
                    "ETA=${etaSeconds}s, " +
                    "delay=${delaySeconds}s, " +
                    "trigger=$triggerAtMillis"
        )
    }

    fun cancelMidJourneyCheckIn(
        context: Context
    ) {
        val intent =
            Intent(
                context,
                JourneyCheckInReceiver::class.java
            ).apply {
                action =
                    JourneyCheckInReceiver.ACTION_CHECK_IN
            }

        val pendingIntent =
            PendingIntent.getBroadcast(
                context,
                REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or
                        PendingIntent.FLAG_IMMUTABLE
            )

        val alarmManager =
            context.getSystemService(
                Context.ALARM_SERVICE
            ) as AlarmManager

        alarmManager.cancel(pendingIntent)
        pendingIntent.cancel()

        Log.d(
            TAG,
            "Check-in alarm cancelled."
        )
    }
}
