package com.project.saaya.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.project.saaya.ui.components.InformationCard
import com.project.saaya.ui.components.OnboardingScaffold
import com.project.saaya.ui.components.SaayaOutlinedTextField
import com.project.saaya.ui.components.SoftNote
import com.project.saaya.ui.state.OnboardingState
import com.project.saaya.ui.theme.SaayaBlack
import com.project.saaya.ui.theme.SaayaBorder
import com.project.saaya.ui.theme.SaayaWhite

@Composable
fun PhoneVerificationScreen(
    state: OnboardingState,
    onBack: () -> Unit,
    onContinue: () -> Unit
) {
    val primaryButtonEnabled = if (state.otpSent) {
        state.otp.length == 6
    } else {
        state.phoneNumber.length == 10
    }

    val primaryButtonText = if (state.otpSent) {
        "Verify & continue"
    } else {
        "Send verification code"
    }

    OnboardingScaffold(
        step = 2,
        title = "Verify your phone number",
        subtitle = "Phone verification is not available. Sign in with Google from the welcome screen.",
        primaryButtonText = primaryButtonText,
        primaryButtonEnabled = false,
        // Legacy mock flow is disabled; Google Sign-In is the only authentication entry.
        onPrimaryButtonClick = {},
        onBack = onBack
    ) {
        InformationCard(
            icon = Icons.Outlined.PhoneAndroid,
            title = "Contact details"
        ) {
            SaayaOutlinedTextField(
                value = state.phoneNumber,
                onValueChange = { input ->
                    state.phoneNumber = input
                        .filter { character ->
                            character.isDigit()
                        }
                        .take(10)

                    state.otp = ""
                    state.otpSent = false
                    state.phoneVerified = false
                },
                label = "Phone number",
                placeholder = "00000 00000",
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Phone
                ),
                leadingContent = {
                    Text(
                        text = "+91",
                        fontWeight = FontWeight.SemiBold
                    )
                }
            )

            if (state.otpSent) {
                Spacer(
                    modifier = Modifier.height(20.dp)
                )

                Text(
                    text = "Enter the 6-digit code",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold
                )

                Spacer(
                    modifier = Modifier.height(10.dp)
                )

                OtpCodeField(
                    value = state.otp,
                    onValueChange = { newOtp ->
                        state.otp = newOtp
                    }
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(
                        onClick = {
                            state.otp = ""

                            /*
                             * Trigger OTP resend here.
                             */
                        }
                    ) {
                        Text("Resend code")
                    }
                }
            }
        }

        Spacer(
            modifier = Modifier.height(16.dp)
        )

        SoftNote(
            text = "Your phone number is not displayed publicly. It is used only for account security and safety features you explicitly enable."
        )
    }
}

@Composable
private fun OtpCodeField(
    value: String,
    onValueChange: (String) -> Unit
) {
    BasicTextField(
        value = value,
        onValueChange = { input ->
            val filteredInput = input
                .filter { character ->
                    character.isDigit()
                }
                .take(6)

            onValueChange(filteredInput)
        },
        modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.NumberPassword
        ),
        cursorBrush = SolidColor(SaayaBlack),
        decorationBox = { innerTextField ->

            Box(
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement =
                        Arrangement.spacedBy(8.dp)
                ) {
                    repeat(6) { index ->

                        val character = value
                            .getOrNull(index)
                            ?.toString()
                            .orEmpty()

                        val activeIndex =
                            value.length.coerceAtMost(5)

                        val isActive =
                            index == activeIndex

                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .height(54.dp),
                            shape = RoundedCornerShape(10.dp),
                            color = SaayaWhite,
                            border = BorderStroke(
                                width = if (isActive) {
                                    1.5.dp
                                } else {
                                    1.dp
                                },
                                color = if (isActive) {
                                    SaayaBlack
                                } else {
                                    SaayaBorder
                                }
                            )
                        ) {
                            Box(
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = character,
                                    style =
                                        MaterialTheme.typography.titleMedium,
                                    fontWeight =
                                        FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }

                /*
                 * Keeps the real text input focusable while
                 * displaying the custom OTP boxes.
                 */
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .alpha(0f)
                ) {
                    innerTextField()
                }
            }
        }
    )
}
