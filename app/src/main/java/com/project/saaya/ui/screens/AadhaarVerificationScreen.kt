package com.project.saaya.ui.screens

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Badge
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.project.saaya.ui.components.InformationCard
import com.project.saaya.ui.components.OnboardingScaffold
import com.project.saaya.ui.components.SaayaOutlinedTextField
import com.project.saaya.ui.components.SoftNote
import com.project.saaya.ui.state.OnboardingState
import com.project.saaya.ui.theme.SaayaBlack
import com.project.saaya.ui.theme.SaayaEmergencyRed
import com.project.saaya.ui.theme.SaayaSuccessGreen
import com.project.saaya.ui.theme.SaayaTextSecondary
import com.project.saaya.ui.theme.SaayaWhite

@Composable
fun AadhaarVerificationScreen(
    state: OnboardingState,
    onContinue: () -> Unit
) {
    var showAadhaarError by rememberSaveable {
        mutableStateOf(false)
    }

    val validName =
        state.fullName.trim().length >= 2

    OnboardingScaffold(
        step = 1,
        title = "Verify your identity",
        subtitle = "Aadhaar verification is not available. Sign in with Google from the welcome screen.",
        primaryButtonText = "Continue to phone verification",
        primaryButtonEnabled = false,
        onPrimaryButtonClick = {}
    ) {
        Text(
            text = "Personal details",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )

        Spacer(Modifier.height(10.dp))

        SaayaOutlinedTextField(
            value = state.fullName,
            onValueChange = { input ->
                state.fullName = input.take(50)
            },
            label = "Full name",
            placeholder = "As per official ID",
            supportingText =
                "This name will appear across your Saaya profile.",
            isError =
                state.fullName.isNotEmpty() && !validName,
            keyboardOptions = KeyboardOptions(
                capitalization =
                    KeyboardCapitalization.Words,
                keyboardType = KeyboardType.Text
            ),
            leadingContent = {
                Icon(
                    imageVector =
                        Icons.Outlined.PersonOutline,
                    contentDescription = null
                )
            }
        )

        Spacer(Modifier.height(22.dp))

        InformationCard(
            icon = Icons.Outlined.Badge,
            title = "Verify with Aadhaar",
            trailing = {
                Text(
                    text = if (state.aadhaarVerified) {
                        "Verified"
                    } else {
                        "Required"
                    },
                    color = if (state.aadhaarVerified) {
                        SaayaSuccessGreen
                    } else {
                        SaayaEmergencyRed
                    },
                    style =
                        MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
        ) {
            Text(
                text = "Complete authorized KYC to confirm that you meet the safety criteria for this network.",
                style = MaterialTheme.typography.bodySmall,
                color = SaayaTextSecondary
            )

            Spacer(Modifier.height(16.dp))

            SaayaOutlinedTextField(
                value = state.aadhaarNumber,
                onValueChange = { input ->
                    state.aadhaarNumber = input
                        .filter { it.isDigit() }
                        .take(12)

                    state.aadhaarVerified = false
                    showAadhaarError = false
                },
                label = "Aadhaar number",
                placeholder = "XXXX XXXX XXXX",
                isError = showAadhaarError,
                supportingText =
                    if (showAadhaarError) {
                        "Enter a valid 12-digit Aadhaar number"
                    } else {
                        "Used only to initiate authorized verification"
                    },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number
                ),
                leadingContent = {
                    Icon(
                        imageVector = Icons.Outlined.Lock,
                        contentDescription = null
                    )
                }
            )

            Spacer(Modifier.height(12.dp))

            Button(
                // No prototype KYC success; this flow is not part of authenticated onboarding.
                onClick = {},
                enabled = false,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = SaayaBlack,
                    contentColor = SaayaWhite
                )
            ) {
                Text(
                    text = if (state.aadhaarVerified) {
                        "Aadhaar verified"
                    } else {
                        "Verify Aadhaar"
                    },
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        SoftNote(
            text = "Your raw Aadhaar number is not stored. Only the verification status is saved on this device."
        )
    }
}
