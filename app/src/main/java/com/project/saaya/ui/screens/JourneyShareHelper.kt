package com.project.saaya.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri

object JourneyShareHelper {

    fun shareJourney(
        context: Context,
        startLocation: String,
        destination: String,
        transportMode: String,
        etaMinutes: Int,
        distanceKm: Double? = null,
        latitude: Double? = null,
        longitude: Double? = null
    ) {
        val message = buildString {
            append("🛡️ Saaya Journey Alert\n\n")
            append("I'm currently travelling.\n\n")
            append("📍 From: $startLocation\n")
            append("📍 To: $destination\n")
            append("🚗 Mode: $transportMode\n")

            if (etaMinutes > 0) {
                append("⏱️ ETA: $etaMinutes min\n")
            }

            distanceKm?.let {
                append("📏 Distance: %.1f km\n".format(it))
            }

            if (latitude != null && longitude != null) {
                append("\n📍 Journey starting point:\n")
                append(
                    "https://www.google.com/maps/search/?api=1&query=$latitude,$longitude\n"
                )
            }

            append("\nI'm sharing my journey through Saaya.")
        }

        // Generic Android share sheet: WhatsApp, Messages/SMS,
        // and other installed compatible apps can appear here.
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, message)
        }

        context.startActivity(
            Intent.createChooser(
                shareIntent,
                "Share journey"
            )
        )
    }

    fun shareJourneyViaMessages(
        context: Context,
        phoneNumber: String?,
        message: String
    ) {
        val smsIntent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("smsto:${phoneNumber.orEmpty()}")
            putExtra("sms_body", message)
        }

        try {
            context.startActivity(smsIntent)
        } catch (_: Exception) {
            val fallback = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, message)
            }
            context.startActivity(
                Intent.createChooser(fallback, "Share journey")
            )
        }
    }
}
