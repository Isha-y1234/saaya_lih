package com.project.saaya.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PhoneInTalk
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.project.saaya.ui.theme.SaayaBlack
import com.project.saaya.ui.theme.SaayaBorder
import com.project.saaya.ui.theme.SaayaTextSecondary
import com.project.saaya.ui.theme.SaayaWhite

@Composable
fun SosContactSection(
    currentNumber: String,
    onSave: (String) -> Unit
) {
    var phoneNumber by rememberSaveable(currentNumber) {
        mutableStateOf(currentNumber)
    }

    var saved by rememberSaveable {
        mutableStateOf(false)
    }

    val digitCount =
        phoneNumber.count { it.isDigit() }

    val isValid =
        digitCount in 10..15

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = SaayaWhite,
        border = BorderStroke(
            width = 1.dp,
            color = SaayaBorder
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = "SOS contact",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )

            Spacer(Modifier.height(5.dp))

            Text(
                text = "This person will be called when you activate SOS.",
                color = SaayaTextSecondary,
                style = MaterialTheme.typography.bodySmall
            )

            Spacer(Modifier.height(14.dp))

            SaayaOutlinedTextField(
                value = phoneNumber,
                onValueChange = { input ->
                    phoneNumber = input
                        .filter { character ->
                            character.isDigit() ||
                                character == '+'
                        }
                        .take(16)

                    saved = false
                },
                label = "Emergency contact number",
                placeholder = "+91 98765 43210",
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Phone
                ),
                leadingContent = {
                    Icon(
                        imageVector =
                            Icons.Outlined.PhoneInTalk,
                        contentDescription = null
                    )
                },
                supportingText =
                    if (saved) {
                        "SOS contact saved"
                    } else {
                        "Include the country code when possible"
                    }
            )

            Spacer(Modifier.height(10.dp))

            Button(
                onClick = {
                    val normalizedNumber =
                        normalizePhoneNumber(phoneNumber)

                    phoneNumber = normalizedNumber
                    onSave(normalizedNumber)
                    saved = true
                },
                enabled = isValid,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = SaayaBlack,
                    contentColor = SaayaWhite
                )
            ) {
                Text(
                    text = "Save SOS contact",
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
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
            // Default Indian country code.
            "+91$digits"
        }

        else -> {
            digits
        }
    }
}
