package com.project.saaya.ui.screens

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.project.saaya.R
import com.project.saaya.data.model.UserProfile
import com.project.saaya.ui.components.HomeTab
import com.project.saaya.ui.components.SaayaBottomNavigationBar
import com.project.saaya.ui.sos.LocalIncidentViewModel
import com.project.saaya.ui.theme.SaayaBlack
import com.project.saaya.ui.theme.SaayaBorder
import com.project.saaya.ui.theme.SaayaEmergencyRed
import com.project.saaya.ui.theme.SaayaSuccessGreen
import com.project.saaya.ui.theme.SaayaSurface
import com.project.saaya.ui.theme.SaayaTextSecondary
import com.project.saaya.ui.theme.SaayaWhite

@Composable
fun HomeScreen(
    userProfile: UserProfile,
    onJourneyClick: () -> Unit,
    onNetworkClick: () -> Unit,
    onActivityClick: () -> Unit,
    onProfileClick: () -> Unit,
    onChatClick: () -> Unit,
    onTrustedCircleClick: () -> Unit,
    onSaveSosContact: (String) -> Unit,
    onLiveSafetyClick: (String) -> Unit = {},
    onIncidentChatClick: (String) -> Unit = {},
    onAllIncidentsClick: () -> Unit = {},
    onEvidenceClick: (String) -> Unit = {},
    onReportsClick: (String) -> Unit = {},
    onResourcesClick: () -> Unit = {},
    onTriggerSos: () -> Unit,
    onResetOnboarding: () -> Unit
) {

    val context = androidx.compose.ui.platform.LocalContext.current

    val incidentViewModel = requireNotNull(LocalIncidentViewModel.current)
    val incidentState by incidentViewModel.uiState.collectAsState()
    val sosActive = incidentState.activeIncident != null

    var currentLocation by remember {
        mutableStateOf<Location?>(null)
    }

    LaunchedEffect(Unit) {

        if (
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            getCurrentLocation(context) {
                currentLocation = it
            }
        }
    }

    val statusTitle = when {
        sosActive -> "SOS activated"
        else -> "You're protected."
    }

    Scaffold(
        containerColor = SaayaWhite,

        bottomBar = {
            SaayaBottomNavigationBar(
                selectedTab = HomeTab.Home,

                onTabSelected = { tab ->

                    when (tab) {
                        HomeTab.Home -> Unit
                        HomeTab.Journey -> onJourneyClick()
                        HomeTab.Network -> onNetworkClick()
                        HomeTab.Activity -> onActivityClick()
                        HomeTab.Profile -> onProfileClick()
                    }
                }
            )
        }

    ) { innerPadding ->

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(SaayaWhite)
                .padding(innerPadding)
                .verticalScroll(
                    rememberScrollState()
                )
                .padding(
                    horizontal = 16.dp,
                    vertical = 12.dp
                )
        ) {

            HomeTopBar(
                onTrustedCircleClick = onTrustedCircleClick,
                onChatClick = onChatClick
            )

            Spacer(
                Modifier.height(20.dp)
            )

            Text(
                text = "HOME",
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = SaayaBlack
            )

            Spacer(
                Modifier.height(12.dp)
            )

            SafetyStatusCard(
                title = statusTitle,
                location = getLocationLabel(currentLocation)
            )

            Spacer(
                Modifier.height(24.dp)
            )

            HomeSosButton(
                sosActive = sosActive,

                onClick = {
                    if (userProfile.sosContactNumber.isBlank()) {
                        onProfileClick()
                    } else if (!sosActive) {
                        onTriggerSos()
                    }
                }
            )

            Text(
                text = if (sosActive) {
                    "SOS incident is active."
                } else {
                    "Press only when you need immediate help."
                },

                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),

                textAlign = TextAlign.Center,

                fontSize = 10.sp,

                color = SaayaTextSecondary
            )

            if (sosActive) {
                TextButton(
                    onClick = { incidentState.activeIncident?.let { incidentViewModel.endIncident(it.id) } },
                    enabled = !incidentState.isLoading,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("End SOS") }
                TextButton(onClick = { incidentState.activeIncident?.let { onEvidenceClick(it.id) } },
                    modifier = Modifier.fillMaxWidth()) { Text("Capture emergency evidence") }
                TextButton(onClick = { incidentState.activeIncident?.let { onLiveSafetyClick(it.id) } },
                    modifier = Modifier.fillMaxWidth()) { Text("Share live safety") }
            }

            Spacer(Modifier.height(24.dp))

            /*
             * INCIDENT HISTORY CARD
             */
            IncidentHistoryAccessCard(
                incidentCount = incidentState.incidents.size,
                onClick = onAllIncidentsClick
            )

            Spacer(Modifier.height(16.dp))

            incidentState.incidents.firstOrNull()?.let { recent ->
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = SaayaSurface,
                    border = BorderStroke(1.dp, SaayaBorder)
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("MOST RECENT INCIDENT", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = SaayaTextSecondary)
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Warning, null, tint = SaayaEmergencyRed, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(recent.status, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.width(8.dp))
                            Text("ID: ${recent.id.takeLast(6)}", fontSize = 11.sp, color = SaayaTextSecondary)
                        }
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(
                                onClick = { onReportsClick(recent.id) },
                                modifier = Modifier.weight(1f)
                            ) { Text("Reports", fontSize = 12.sp) }
                            
                            TextButton(
                                onClick = { onIncidentChatClick(recent.id) },
                                modifier = Modifier.weight(1f)
                            ) { Text("Chat Log", fontSize = 12.sp) }
                        }
                    }
                }
            }

            Spacer(
                Modifier.height(28.dp)
            )

            IncidentFeed()

            Spacer(
                Modifier.height(70.dp)
            )
        }
    }
}

@Composable
private fun IncidentHistoryAccessCard(
    incidentCount: Int,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = SaayaBlack,
        onClick = onClick
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(44.dp),
                shape = CircleShape,
                color = SaayaWhite.copy(alpha = 0.15f)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Outlined.History,
                        contentDescription = null,
                        tint = SaayaWhite,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
            
            Spacer(Modifier.width(16.dp))
            
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Incident Logs",
                    color = SaayaWhite,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = if (incidentCount > 0) "$incidentCount saved incidents" else "No incidents recorded",
                    color = SaayaWhite.copy(alpha = 0.6f),
                    fontSize = 11.sp
                )
            }
            
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.ArrowForward,
                contentDescription = null,
                tint = SaayaWhite,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun HomeTopBar(
    onTrustedCircleClick: () -> Unit,
    onChatClick: () -> Unit
) {

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {

        Icon(
            painter = painterResource(id = R.drawable.ic_saaya_logo),
            contentDescription = "Saaya",
            tint = Color.Unspecified,
            modifier = Modifier.size(26.dp)
        )

        Spacer(
            Modifier.width(8.dp)
        )

        Text(
            text = "Saaya",
            color = SaayaBlack,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(
            Modifier.weight(1f)
        )

        IconButton(
            onClick = onTrustedCircleClick
        ) {

            Icon(
                imageVector = Icons.Outlined.Person,
                contentDescription = "Trusted Circle",
                tint = SaayaBlack
            )
        }

        IconButton(
            onClick = onChatClick
        ) {

            Icon(
                imageVector = Icons.Outlined.ChatBubbleOutline,
                contentDescription = "Chat",
                tint = SaayaBlack
            )
        }
    }
}

@Composable
private fun SafetyStatusCard(
    title: String,
    location: String
) {

    Surface(
        modifier = Modifier.fillMaxWidth(),

        shape = RoundedCornerShape(16.dp),

        color = SaayaSuccessGreen.copy(
            alpha = 0.07f
        ),

        border = BorderStroke(
            1.dp,
            SaayaSuccessGreen.copy(
                alpha = 0.16f
            )
        )
    ) {

        Row(
            modifier = Modifier.padding(16.dp),

            verticalAlignment =
                Alignment.CenterVertically
        ) {

            Surface(
                modifier = Modifier.size(42.dp),

                shape = CircleShape,

                color = SaayaSuccessGreen.copy(
                    alpha = 0.10f
                )
            ) {

                Box(
                    contentAlignment =
                        Alignment.Center
                ) {

                    Icon(
                        imageVector =
                            Icons.Filled.Shield,

                        contentDescription =
                            null,

                        tint =
                            SaayaSuccessGreen,

                        modifier =
                            Modifier.size(22.dp)
                    )
                }
            }

            Spacer(
                Modifier.width(12.dp)
            )

            Column {

                @Suppress("DEPRECATION")
                Text(
                    text = title,

                    color =
                        SaayaSuccessGreen,

                    fontSize =
                        13.sp,

                    fontWeight =
                        FontWeight.SemiBold
                )

                Spacer(
                    Modifier.height(3.dp)
                )

                Text(
                    text = location,

                    color =
                        SaayaTextSecondary,

                    fontSize =
                        10.sp
                )
            }
        }
    }
}

@Composable
private fun HomeSosButton(
    sosActive: Boolean,
    onClick: () -> Unit
) {

    val pulse = remember {
        Animatable(1f)
    }

    LaunchedEffect(Unit) {

        while (true) {

            pulse.animateTo(
                targetValue = 1.18f,

                animationSpec =
                    tween(
                        durationMillis = 1700,
                        easing = FastOutSlowInEasing
                    )
            )

            pulse.animateTo(
                targetValue = 1f,

                animationSpec =
                    tween(
                        durationMillis = 1700,
                        easing = FastOutSlowInEasing
                    )
            )
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(205.dp),

        contentAlignment =
            Alignment.Center
    ) {

        Surface(
            modifier = Modifier
                .size(190.dp)
                .scale(pulse.value),

            shape = CircleShape,

            color = SaayaEmergencyRed.copy(
                alpha = 0.035f
            )
        ) {}

        Surface(
            modifier = Modifier
                .size(174.dp)
                .scale(pulse.value * 0.97f),

            shape = CircleShape,

            color = SaayaEmergencyRed.copy(
                alpha = 0.055f
            )
        ) {}

        Surface(
            modifier = Modifier.size(158.dp),

            shape = CircleShape,

            color = SaayaEmergencyRed.copy(
                alpha = 0.09f
            )
        ) {}

        Surface(
            onClick = onClick,

            modifier = Modifier.size(132.dp),

            shape = CircleShape,

            color =
                if (sosActive) {
                    Color(0xFF8F2525)
                } else {
                    SaayaEmergencyRed
                },

            shadowElevation = 10.dp
        ) {

            Column(
                modifier =
                    Modifier.fillMaxSize(),

                horizontalAlignment =
                    Alignment.CenterHorizontally,

                verticalArrangement =
                    Arrangement.Center
            ) {

                Text(
                    text = "SOS",

                    color = SaayaWhite,

                    fontSize = 29.sp,

                    fontWeight = FontWeight.Black
                )

                Spacer(
                    Modifier.height(3.dp)
                )

                Text(
                    text =
                        if (sosActive)
                            "ACTIVE"
                        else
                            "Emergency",

                    color =
                        SaayaWhite.copy(
                            alpha = 0.78f
                        ),

                    fontSize = 10.sp,

                    fontWeight =
                        FontWeight.Medium
                )
            }
        }
    }
}

@Composable
private fun IncidentFeed() {

    Row(
        modifier =
            Modifier.fillMaxWidth(),

        verticalAlignment =
            Alignment.CenterVertically
    ) {

        Text(
            text = "RECENT ACTIVITY",

            color = SaayaBlack,

            fontSize = 11.sp,

            fontWeight = FontWeight.Bold
        )

        Spacer(
            Modifier.weight(1f)
        )

        Text(
            text = "Today",

            color = SaayaTextSecondary,

            fontSize = 9.sp
        )
    }

    Spacer(
        Modifier.height(10.dp)
    )

    IncidentCard(
        time = "10 mins ago",
        location = "Central Park",
        title = "Suspicious Activity Reported",
        description =
            "User reported an individual acting suspiciously near the south entrance. Police notified."
    )

    Spacer(
        Modifier.height(10.dp)
    )

    IncidentCard(
        time = "32 mins ago",
        location = "Metro Gate 2",
        title = "Safety Alert",
        description =
            "Multiple users reported an unsafe situation near the station entrance."
    )
}

@Composable
private fun IncidentCard(
    time: String,
    location: String,
    title: String,
    description: String
) {

    Surface(
        modifier =
            Modifier.fillMaxWidth(),

        shape =
            RoundedCornerShape(12.dp),

        color =
            SaayaSurface,

        border =
            BorderStroke(
                1.dp,
                SaayaBorder
            )
    ) {

        Row(
            modifier =
                Modifier.padding(14.dp),

            verticalAlignment =
                Alignment.Top
        ) {

            Surface(
                modifier =
                    Modifier.size(34.dp),

                shape =
                    CircleShape,

                color =
                    Color(0xFFF6EAEA)
            ) {

                Box(
                    contentAlignment =
                        Alignment.Center
                ) {

                    Icon(
                        imageVector =
                            Icons.Outlined.Warning,

                        contentDescription =
                            null,

                        tint =
                            SaayaEmergencyRed,

                        modifier =
                            Modifier.size(17.dp)
                    )
                }
            }

            Spacer(
                Modifier.width(10.dp)
            )

            Column(
                modifier =
                    Modifier.weight(1f)
            ) {

                Text(
                    text =
                        "$time • $location",

                    color =
                        SaayaTextSecondary,

                    fontSize =
                        9.sp
                )

                Spacer(
                    Modifier.height(5.dp)
                )

                @Suppress("DEPRECATION")
                Text(
                    text = title,

                    color =
                        SaayaBlack,

                    fontSize =
                        12.sp,

                    fontWeight =
                        FontWeight.SemiBold
                )

                Spacer(
                    Modifier.height(3.dp)
                )

                Text(
                    text = description,

                    color =
                        SaayaTextSecondary,

                    fontSize =
                        10.sp,

                    lineHeight =
                        15.sp
                )
            }
        }
    }
}

private fun getLocationLabel(
    location: Location?
): String {

    return if (location != null) {
        "Downtown Metro Core"
    } else {
        "Location unavailable"
    }
}

@SuppressLint("MissingPermission")
private fun getCurrentLocation(
    context: Context,
    onLocation: (Location) -> Unit
) {

    val client =
        LocationServices
            .getFusedLocationProviderClient(context)

    client.lastLocation
        .addOnSuccessListener {

            if (it != null) {

                onLocation(it)

            } else {

                val request =
                    LocationRequest.Builder(
                        Priority.PRIORITY_HIGH_ACCURACY,
                        1000L
                    )
                        .setMaxUpdates(1)
                        .build()

                val callback =
                    object : LocationCallback() {

                        override fun onLocationResult(
                            result: LocationResult
                        ) {

                            result.lastLocation
                                ?.let(onLocation)

                            client.removeLocationUpdates(
                                this
                            )
                        }
                    }

                client.requestLocationUpdates(
                    request,
                    callback,
                    context.mainLooper
                )
            }
        }
}
