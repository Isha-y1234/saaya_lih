package com.project.saaya.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.outlined.Badge
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.PrivacyTip
import androidx.compose.material.icons.outlined.VolunteerActivism
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FabPosition
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.project.saaya.data.model.UserProfile
import com.project.saaya.ui.components.HomeTab
import com.project.saaya.ui.components.SaayaBottomNavigationBar
import com.project.saaya.ui.components.SaayaTopBar
import com.project.saaya.ui.components.SmallSosButton
import com.project.saaya.ui.sos.rememberSosCallAction
import com.project.saaya.ui.theme.SaayaBlack
import com.project.saaya.ui.theme.SaayaBorder
import com.project.saaya.ui.theme.SaayaEmergencyRed
import com.project.saaya.ui.theme.SaayaSuccessGreen
import com.project.saaya.ui.theme.SaayaSurface
import com.project.saaya.ui.theme.SaayaTextSecondary
import com.project.saaya.ui.theme.SaayaWhite

@Composable
fun ProfileScreen(
    profile: UserProfile,
    onHomeClick: () -> Unit,
    onJourneyClick: () -> Unit,
    onNetworkClick: () -> Unit,
    onActivityClick: () -> Unit,
    onTrustedCircleClick: () -> Unit,
    onChatClick: () -> Unit,
    onSaveSosContact: (String) -> Unit,
    onResetOnboarding: () -> Unit,
    onLiveSafetySessions: () -> Unit = {},
    saayaId: String? = null,
    isSaayaIdLoading: Boolean = false,
    saayaIdError: String? = null,
    onRetrySaayaId: () -> Unit = {}
) {

    val context = LocalContext.current
    var notificationSettingsError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(saayaId) {
        if (saayaId.isNullOrBlank()) onRetrySaayaId()
    }

    var showResetDialog by rememberSaveable {
        mutableStateOf(false)
    }

    var sosError by rememberSaveable {
        mutableStateOf<String?>(null)
    }

    val callSosContact = rememberSosCallAction(
        phoneNumber = profile.sosContactNumber,
        onError = {
            sosError = it
        }
    )

    Scaffold(
        containerColor = SaayaWhite,

        bottomBar = {
            SaayaBottomNavigationBar(
                selectedTab = HomeTab.Profile,
                onTabSelected = { tab ->
                    when (tab) {
                        HomeTab.Home -> onHomeClick()
                        HomeTab.Journey -> onJourneyClick()
                        HomeTab.Network -> onNetworkClick()
                        HomeTab.Activity -> onActivityClick()
                        HomeTab.Profile -> Unit
                    }
                }
            )
        },

        floatingActionButton = {
            SmallSosButton(
                onClick = {
                    callSosContact()
                }
            )
        },

        floatingActionButtonPosition = FabPosition.Start

    ) { contentPadding ->

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .verticalScroll(
                    rememberScrollState()
                )
                .padding(
                    horizontal = 16.dp
                )
                .padding(
                    top = 28.dp,
                    bottom = 24.dp
                )
        ) {

            SaayaTopBar(
                onTrustedCircleClick = onTrustedCircleClick,
                onChatClick = onChatClick
            )

            Spacer(
                Modifier.height(22.dp)
            )

            Text(
                text = "PROFILE",
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = SaayaBlack
            )

            Spacer(
                Modifier.height(20.dp)
            )

            /*
             * PROFILE HERO
             */

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                color = Color(0xFFF8F6F8),
                border = BorderStroke(
                    1.dp,
                    Color(0xFFEAE5EA)
                )
            ) {
                Column(
                    modifier = Modifier.padding(
                        vertical = 22.dp,
                        horizontal = 18.dp
                    ),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Surface(
                        modifier = Modifier.size(82.dp),
                        shape = CircleShape,
                        color = Color(0xFF343333)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = profile.fullName
                                    .trim()
                                    .firstOrNull()
                                    ?.uppercase()
                                    ?: "S",
                                color = SaayaWhite,
                                fontSize = 31.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Spacer(Modifier.height(13.dp))

                    Text(
                        text = profile.fullName.ifBlank { "Saaya User" },
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = SaayaBlack,
                        textAlign = TextAlign.Center
                    )

                    Spacer(Modifier.height(6.dp))

                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = Color(0xFFEFF4F0)
                    ) {
                        Row(
                            modifier = Modifier.padding(
                                horizontal = 11.dp,
                                vertical = 6.dp
                            ),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Shield,
                                contentDescription = null,
                                tint = SaayaSuccessGreen,
                                modifier = Modifier.size(15.dp)
                            )

                            Spacer(Modifier.width(5.dp))

                            Text(
                                text = "Signed in with Google",
                                color = SaayaSuccessGreen,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            /*
             * PERSONAL DETAILS
             */

            SectionTitle(
                title = "Personal details",
                subtitle = "Your basic account information"
            )

            Spacer(Modifier.height(10.dp))

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = SaayaWhite,
                border = BorderStroke(
                    1.dp,
                    SaayaBorder
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    ProfileSaayaId(saayaId, isSaayaIdLoading, saayaIdError, onRetrySaayaId)
                    ProfileDivider()
                    ProfileDetailRow(
                        icon = Icons.Outlined.Badge,
                        label = "Full name",
                        value = profile.fullName.ifBlank { "Not provided" }
                    )
                    ProfileDivider()
                    ProfileDetailRow(
                        icon = Icons.Outlined.PhoneAndroid,
                        label = "Phone number",
                        value = maskPhoneNumber(profile.phoneNumber)
                    )
                }
            }

            Spacer(Modifier.height(22.dp))

            /*
             * SAFETY & NOTIFICATIONS
             */

            SectionTitle(
                title = "Safety settings",
                subtitle = "Manage your protection and alerts"
            )

            Spacer(Modifier.height(10.dp))

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = SaayaWhite,
                border = BorderStroke(1.dp, SaayaBorder)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Outlined.NotificationsActive,
                            contentDescription = null,
                            tint = SaayaBlack,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Real-time Alerts", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                            Text(
                                "Stay informed about nearby safety incidents and check-ins. We recommend keeping these on for your protection.",
                                fontSize = 11.sp,
                                color = SaayaTextSecondary,
                                lineHeight = 15.sp
                            )
                        }
                    }
                    
                    Spacer(Modifier.height(12.dp))
                    
                    OutlinedButton(
                        onClick = {
                            notificationSettingsError = null
                            val intent = if (Build.VERSION.SDK_INT >= 26) Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                            else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                            try {
                                context.startActivity(intent)
                            } catch (_: android.content.ActivityNotFoundException) {
                                notificationSettingsError = "Notification settings are unavailable on this device."
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("Configure Notifications", fontSize = 12.sp)
                    }
                    
                    notificationSettingsError?.let { 
                        Text(it, color = MaterialTheme.colorScheme.error, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp)) 
                    }

                    ProfileDivider()

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Outlined.History,
                            contentDescription = null,
                            tint = SaayaBlack,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Live Safety Sessions", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                            Text("Review your recent shared safety journeys and recordings.", fontSize = 11.sp, color = SaayaTextSecondary)
                        }
                        TextButton(onClick = onLiveSafetySessions) {
                            Text("View", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            Spacer(Modifier.height(22.dp))

            /*
             * VERIFICATION
             */

            SectionTitle(
                title = "Verification",
                subtitle = "Your Saaya account status"
            )

            Spacer(Modifier.height(10.dp))

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = SaayaWhite,
                border = BorderStroke(
                    1.dp,
                    SaayaBorder
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    ProfileDetailRow(
                        icon = Icons.Outlined.Badge,
                        label = "Aadhaar verification",
                        value = verificationText(profile.aadhaarVerified),
                        valueColor = verificationColor(profile.aadhaarVerified)
                    )
                    ProfileDivider()
                    ProfileDetailRow(
                        icon = Icons.Outlined.PhoneAndroid,
                        label = "Phone verification",
                        value = verificationText(profile.phoneVerified),
                        valueColor = verificationColor(profile.phoneVerified)
                    )
                    ProfileDivider()
                    ProfileDetailRow(
                        icon = Icons.Outlined.Groups,
                        label = "Community",
                        value = if (profile.identifiesAsWoman) "Women-only member" else "Not confirmed"
                    )
                    ProfileDivider()
                    ProfileDetailRow(
                        icon = Icons.Outlined.VolunteerActivism,
                        label = "Volunteer status",
                        value = if (profile.registerAsVolunteer) "Registered" else "Standard member"
                    )
                    ProfileDivider()
                    ProfileDetailRow(
                        icon = Icons.Outlined.PrivacyTip,
                        label = "Privacy consent",
                        value = if (profile.privacyAccepted) "Accepted" else "Not accepted",
                        valueColor = if (profile.privacyAccepted) SaayaSuccessGreen else SaayaEmergencyRed
                    )
                }
            }

            Spacer(Modifier.height(22.dp))

            /*
             * PRIVACY CARD
             */

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = Color(0xFFF7F7F5),
                border = BorderStroke(
                    1.dp,
                    Color(0xFFE9E8E4)
                )
            ) {
                Row(
                    modifier = Modifier.padding(15.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Surface(
                        modifier = Modifier.size(38.dp),
                        shape = CircleShape,
                        color = Color(0xFFEDEDE8)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Outlined.PrivacyTip,
                                contentDescription = null,
                                tint = Color(0xFF73736D),
                                modifier = Modifier.size(19.dp)
                            )
                        }
                    }

                    Spacer(Modifier.width(11.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Your privacy matters",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = SaayaBlack
                        )

                        Spacer(Modifier.height(4.dp))

                        Text(
                            text = "Your basic profile is stored locally on this device. Raw Aadhaar and OTP information are never saved.",
                            color = SaayaTextSecondary,
                            style = MaterialTheme.typography.bodySmall,
                            lineHeight = 15.sp
                        )
                    }
                }
            }

            Spacer(Modifier.height(22.dp))

            /*
             * RESET
             */

            Text(
                text = "ACCOUNT",
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = SaayaTextSecondary
            )

            Spacer(Modifier.height(9.dp))

            OutlinedButton(
                onClick = { showResetDialog = true },
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = RoundedCornerShape(13.dp),
                border = BorderStroke(
                    width = 1.dp,
                    color = SaayaEmergencyRed.copy(alpha = 0.65f)
                )
            ) {
                Text(
                    text = "Sign out",
                    color = SaayaEmergencyRed,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 12.sp
                )
            }

            Spacer(Modifier.height(12.dp))

            Text(
                text = "You can complete onboarding again after signing out.",
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
                fontSize = 8.sp,
                color = SaayaTextSecondary
            )

            Spacer(Modifier.height(20.dp))
        }
    }

    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text("Sign out of Saaya?") },
            text = { Text("Your session and local preferences will be cleared. Your trusted contacts stay saved in your account.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showResetDialog = false
                        onResetOnboarding()
                    }
                ) {
                    Text(text = "Sign out", color = SaayaEmergencyRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) { Text("Cancel") }
            }
        )
    }

    sosError?.let { error ->
        AlertDialog(
            onDismissRequest = { sosError = null },
            title = { Text("SOS Error") },
            text = { Text(error) },
            confirmButton = {
                TextButton(onClick = { sosError = null }) { Text("OK") }
            }
        )
    }
}


@Composable
private fun SectionTitle(
    title: String,
    subtitle: String
) {
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = SaayaBlack
        )

        Spacer(Modifier.height(3.dp))

        Text(
            text = subtitle,
            fontSize = 9.sp,
            color = SaayaTextSecondary
        )
    }
}


@Composable
private fun ProfileDetailRow(
    icon: ImageVector,
    label: String,
    value: String,
    valueColor: Color = SaayaBlack
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            modifier = Modifier.size(38.dp),
            shape = CircleShape,
            color = SaayaSurface
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = SaayaBlack,
                    modifier = Modifier.size(19.dp)
                )
            }
        }

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                color = SaayaTextSecondary,
                style = MaterialTheme.typography.bodySmall
            )

            Spacer(Modifier.height(2.dp))

            Text(
                text = value,
                color = valueColor,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}


@Composable
private fun ProfileDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(vertical = 14.dp),
        color = SaayaBorder
    )
}


private fun maskPhoneNumber(phoneNumber: String): String {
    if (phoneNumber.length < 4) return "Not provided"
    return "+91 ••••••${phoneNumber.takeLast(4)}"
}


private fun verificationText(verified: Boolean): String {
    return if (verified) "Verified" else "Not verified"
}


private fun verificationColor(verified: Boolean): Color {
    return if (verified) SaayaSuccessGreen else SaayaEmergencyRed
}


@Composable
private fun ProfileSaayaId(saayaId: String?, loading: Boolean, error: String?, onRetry: () -> Unit) {
    val context = LocalContext.current
    val clipboard = remember(context) { context.getSystemService(ClipboardManager::class.java) }
    var copied by remember(saayaId) { mutableStateOf(false) }
    var copyError by remember(saayaId) { mutableStateOf<String?>(null) }
    LaunchedEffect(copied) {
        if (copied) { delay(2000); copied = false }
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Your Saaya ID", fontSize = 12.sp, color = SaayaTextSecondary)
                Text(if (loading) "Loading Saaya ID?" else saayaId?.takeIf { it.isNotBlank() } ?: "Saaya ID unavailable",
                    fontSize = 13.sp, color = SaayaBlack, fontWeight = FontWeight.Medium)
            }
            if (loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            else IconButton(enabled = !saayaId.isNullOrBlank(), onClick = {
                val fullId = saayaId?.takeIf { it.isNotBlank() } ?: return@IconButton
                try {
                    clipboard.setPrimaryClip(ClipData.newPlainText("Saaya ID", fullId))
                    copyError = null
                    copied = true
                } catch (_: Exception) {
                    copyError = "Could not copy your Saaya ID. Please retry."
                }
            }) { Icon(Icons.Outlined.ContentCopy, "Copy Saaya ID", tint = SaayaTextSecondary) }
        }
        if (copied) Text("Saaya ID copied", fontSize = 12.sp, color = SaayaSuccessGreen)
        (copyError ?: error)?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
        if (!loading && (saayaId.isNullOrBlank() || error != null)) {
            TextButton(onClick = onRetry) { Text("Retry Saaya ID") }
        }
    }
}
