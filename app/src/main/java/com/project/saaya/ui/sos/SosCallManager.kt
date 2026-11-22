package com.project.saaya.ui.sos

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

@Composable
fun rememberSosCallAction(
    phoneNumber: String,
    onError: (String) -> Unit,
    latitude: Double? = null,
    longitude: Double? = null
): () -> Unit {
    val incidents = LocalIncidentViewModel.current
    return {
        val number = normalizePhoneNumber(phoneNumber)
        when {
            number.isBlank() -> onError("Add an SOS contact from your Profile first.")
            incidents == null -> onError("Sign in before starting SOS.")
            else -> incidents.startSos(number, latitude, longitude)
        }
    }
}

@Composable
internal fun rememberEmergencyCallAction(
    phoneNumber: String,
    onError: (String) -> Unit
): () -> Unit {
    val context = LocalContext.current

    val latestPhoneNumber by rememberUpdatedState(
        phoneNumber
    )

    val latestOnError by rememberUpdatedState(
        onError
    )

    var pendingPhoneNumber by remember {
        mutableStateOf<String?>(null)
    }

    val permissionLauncher =
        rememberLauncherForActivityResult(
            contract =
                ActivityResultContracts.RequestPermission()
        ) { permissionGranted ->

            val number = pendingPhoneNumber
            pendingPhoneNumber = null

            if (number != null) {
                if (permissionGranted) {
                    placeDirectCall(
                        context = context,
                        phoneNumber = number,
                        onError = latestOnError
                    )
                } else {
                    /*
                     * Permission denied: open the dialler so
                     * the user can manually confirm the call.
                     */
                    openDialler(
                        context = context,
                        phoneNumber = number,
                        onError = latestOnError
                    )
                }
            }
        }

    return {
        val normalizedNumber =
            normalizePhoneNumber(latestPhoneNumber)

        when {
            normalizedNumber.isBlank() -> {
                latestOnError(
                    "Add an SOS contact from your Profile first."
                )
            }

            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CALL_PHONE
            ) == PackageManager.PERMISSION_GRANTED -> {
                placeDirectCall(
                    context = context,
                    phoneNumber = normalizedNumber,
                    onError = latestOnError
                )
            }

            else -> {
                pendingPhoneNumber = normalizedNumber

                permissionLauncher.launch(
                    Manifest.permission.CALL_PHONE
                )
            }
        }
    }
}

private fun placeDirectCall(
    context: Context,
    phoneNumber: String,
    onError: (String) -> Unit
) {
    val callIntent = Intent(
        Intent.ACTION_CALL,
        Uri.fromParts(
            "tel",
            phoneNumber,
            null
        )
    ).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    try {
        context.startActivity(callIntent)
    } catch (exception: SecurityException) {
        openDialler(
            context = context,
            phoneNumber = phoneNumber,
            onError = onError
        )
    } catch (exception: ActivityNotFoundException) {
        onError(
            "No calling application was found on this device."
        )
    }
}

private fun openDialler(
    context: Context,
    phoneNumber: String,
    onError: (String) -> Unit
) {
    val dialIntent = Intent(
        Intent.ACTION_DIAL,
        Uri.fromParts(
            "tel",
            phoneNumber,
            null
        )
    ).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    try {
        context.startActivity(dialIntent)
    } catch (exception: ActivityNotFoundException) {
        onError(
            "No phone dialler was found on this device."
        )
    }
}

private fun normalizePhoneNumber(
    input: String
): String {
    val trimmedInput = input.trim()

    val digits = trimmedInput.filter {
        it.isDigit()
    }

    if (digits.isBlank()) {
        return ""
    }

    return when {
        trimmedInput.startsWith("+") -> {
            "+$digits"
        }

        digits.length == 10 -> {
            "+91$digits"
        }

        else -> {
            digits
        }
    }
}
