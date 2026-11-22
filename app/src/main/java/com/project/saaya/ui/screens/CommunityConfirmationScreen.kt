package com.project.saaya.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.PrivacyTip
import androidx.compose.material.icons.outlined.VolunteerActivism
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.project.saaya.ui.components.InformationCard
import com.project.saaya.ui.components.OnboardingScaffold
import com.project.saaya.ui.state.OnboardingState
import com.project.saaya.ui.theme.SaayaSurface
import com.project.saaya.ui.theme.SaayaTextSecondary

@Composable
fun CommunityConfirmationScreen(
    state: OnboardingState,
    onBack: () -> Unit,
    onComplete: () -> Unit
) {
    var showPrivacyPolicy by rememberSaveable {
        mutableStateOf(false)
    }

    OnboardingScaffold(
        step = 2,
        totalSteps = 2,
        title = "Join the Saaya community",
        subtitle = "Confirm your eligibility and choose how you would like to participate.",
        primaryButtonText = "Complete registration",
        primaryButtonEnabled =
            state.identifiesAsWoman &&
                state.privacyAccepted,
        onPrimaryButtonClick = onComplete,
        onBack = onBack
    ) {
        InformationCard(
            icon = Icons.Outlined.Groups,
            title = "Women-only community"
        ) {
            Text(
                text = "Confirm your eligibility below. Google Sign-In verifies your account, not your age or gender.",
                color = SaayaTextSecondary,
                style = MaterialTheme.typography.bodySmall
            )

            Spacer(
                modifier = Modifier.height(12.dp)
            )

            ConfirmationRow(
                checked = state.identifiesAsWoman,
                onCheckedChange = { isChecked ->
                    state.identifiesAsWoman = isChecked
                },
                text = "I confirm that I identify as a woman and am at least 18 years old."
            )
        }

        Spacer(
            modifier = Modifier.height(16.dp)
        )

        InformationCard(
            icon = Icons.Outlined.VolunteerActivism,
            title = "Register as a volunteer",
            trailing = {
                Switch(
                    checked = state.registerAsVolunteer,
                    onCheckedChange = { isChecked ->
                        state.registerAsVolunteer = isChecked
                    }
                )
            }
        ) {
            Text(
                text = "Volunteer accounts can respond to nearby community support requests after additional verification.",
                color = SaayaTextSecondary,
                style = MaterialTheme.typography.bodySmall
            )
        }

        Spacer(
            modifier = Modifier.height(16.dp)
        )

        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = SaayaSurface,
            shape = MaterialTheme.shapes.medium
        ) {
            Row(
                modifier = Modifier.padding(14.dp),
                verticalAlignment = Alignment.Top
            ) {
                Checkbox(
                    checked = state.privacyAccepted,
                    onCheckedChange = { isChecked ->
                        state.privacyAccepted = isChecked
                    }
                )

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(top = 11.dp)
                ) {
                    Text(
                        text = "I agree to Saaya's Privacy Policy and consent to identity and phone verification for platform safety.",
                        style = MaterialTheme.typography.bodySmall
                    )

                    TextButton(
                        onClick = {
                            showPrivacyPolicy = true
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.PrivacyTip,
                            contentDescription = null
                        )

                        Text("View Privacy Policy")
                    }
                }
            }
        }
    }

    if (showPrivacyPolicy) {
        AlertDialog(
            onDismissRequest = {
                showPrivacyPolicy = false
            },
            title = {
                Text("Privacy Policy")
            },
            text = {
                Text(
                    text = "This is currently a placeholder. Replace it with your reviewed policy covering verification data, retention, user controls and emergency-data sharing before release."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showPrivacyPolicy = false
                    }
                ) {
                    Text("Close")
                }
            }
        )
    }
}

@Composable
private fun ConfirmationRow(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    text: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange
        )

        Text(
            text = text,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium
        )
    }
}
