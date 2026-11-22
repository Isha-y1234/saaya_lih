package com.project.saaya.ui.screens

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.Phone
import androidx.compose.material.icons.outlined.Gavel
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.FabPosition
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.Circle
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.rememberMarkerState
import com.google.maps.android.compose.rememberCameraPositionState
import com.project.saaya.data.model.UserProfile
import com.project.saaya.ui.components.HomeTab
import com.project.saaya.ui.components.SaayaBottomNavigationBar
import com.project.saaya.ui.components.SaayaTopBar
import com.project.saaya.ui.components.SmallSosButton
import com.project.saaya.ui.sos.rememberSosCallAction
import com.project.saaya.ui.theme.SaayaBlack
import com.project.saaya.ui.theme.SaayaEmergencyRed
import com.project.saaya.ui.theme.SaayaSuccessGreen
import com.project.saaya.ui.theme.SaayaSurface
import com.project.saaya.ui.theme.SaayaTextSecondary
import com.project.saaya.ui.theme.SaayaWhite

data class SafetyPlace(
    val name: String,
    val type: SafetyPlaceType,
    val location: LatLng
)

enum class SafetyPlaceType {
    POLICE,
    HOSPITAL,
    HELPLINE
}

data class UserCluster(
    val location: LatLng,
    val userCount: Int
)

data class OfflineHelpline(
    val name: String,
    val number: String,
    val description: String
)

private val FACULTY_OF_TECHNOLOGY = LatLng(28.68753, 77.21415)
private const val CAMPUS_BOUNDARY_RADIUS = 100.0

@Composable
fun NetworkScreen(
    userProfile: UserProfile,
    onHomeClick: () -> Unit,
    onJourneyClick: () -> Unit,
    onActivityClick: () -> Unit,
    onProfileClick: () -> Unit,
    onTrustedCircleClick: () -> Unit,
    onChatClick: () -> Unit,
    onResourcesClick: () -> Unit = {}
) {

    val context = androidx.compose.ui.platform.LocalContext.current
    var currentLocation by remember { mutableStateOf<Location?>(null) }
    var showHelplines by rememberSaveable { mutableStateOf(true) }
    var sosError by rememberSaveable { mutableStateOf<String?>(null) }

    val callSosContact = rememberSosCallAction(
        phoneNumber = userProfile.sosContactNumber,
        onError = { sosError = it }
    )

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            getCurrentLocation(context) { currentLocation = it }
        }
    }

    LaunchedEffect(Unit) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            getCurrentLocation(context) { currentLocation = it }
        } else {
            locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    val cameraPositionState = rememberCameraPositionState()
    LaunchedEffect(Unit) {
        cameraPositionState.animate(CameraUpdateFactory.newLatLngZoom(FACULTY_OF_TECHNOLOGY, 17f))
    }

    val safetyPlaces = remember {
        listOf(
            SafetyPlace("Nearby Police Station", SafetyPlaceType.POLICE, LatLng(28.68820, 77.21360)),
            SafetyPlace("Nearby Hospital", SafetyPlaceType.HOSPITAL, LatLng(28.68670, 77.21520)),
            SafetyPlace("Saaya Help Point", SafetyPlaceType.HELPLINE, LatLng(28.68790, 77.21500))
        )
    }

    val userClusters = remember {
        listOf(
            UserCluster(LatLng(28.68765, 77.21425), 8),
            UserCluster(LatLng(28.68720, 77.21375), 4),
            UserCluster(LatLng(28.68805, 77.21470), 6)
        )
    }

    val offlineHelplines = remember {
        listOf(
            OfflineHelpline("Women Helpline", "181", "Women in distress"),
            OfflineHelpline("Police Emergency", "112", "Emergency response"),
            OfflineHelpline("Women Police Helpline", "1091", "Women police assistance"),
            OfflineHelpline("Ambulance", "108", "Medical emergency"),
            OfflineHelpline("Child Helpline", "1098", "Child emergency support")
        )
    }

    Scaffold(
        containerColor = SaayaWhite,
        bottomBar = {
            SaayaBottomNavigationBar(
                selectedTab = HomeTab.Network,
                onTabSelected = { tab ->
                    when (tab) {
                        HomeTab.Home -> onHomeClick()
                        HomeTab.Journey -> onJourneyClick()
                        HomeTab.Network -> Unit
                        HomeTab.Activity -> onActivityClick()
                        HomeTab.Profile -> onProfileClick()
                    }
                }
            )
        },
        floatingActionButton = {
            SmallSosButton(onClick = {
                if (userProfile.sosContactNumber.isBlank()) {
                    onProfileClick()
                } else {
                    callSosContact()
                }
            })
        },
        floatingActionButtonPosition = FabPosition.Start
    ) { padding ->

        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            GoogleMap(
                modifier = Modifier.fillMaxSize(),
                cameraPositionState = cameraPositionState,
                properties = MapProperties(isMyLocationEnabled = currentLocation != null),
                uiSettings = MapUiSettings(zoomControlsEnabled = false, compassEnabled = false, mapToolbarEnabled = false, myLocationButtonEnabled = true)
            ) {
                Circle(center = FACULTY_OF_TECHNOLOGY, radius = CAMPUS_BOUNDARY_RADIUS, fillColor = SaayaEmergencyRed.copy(alpha = 0.07f), strokeColor = SaayaEmergencyRed.copy(alpha = 0.45f), strokeWidth = 2.5f)
                Marker(state = rememberMarkerState(position = FACULTY_OF_TECHNOLOGY), title = "Faculty of Technology", snippet = "100 m Saaya community zone")
                safetyPlaces.forEach { place ->
                    Marker(state = rememberMarkerState(position = place.location), title = place.name, snippet = when (place.type) { SafetyPlaceType.POLICE -> "Police assistance"; SafetyPlaceType.HOSPITAL -> "Medical assistance"; SafetyPlaceType.HELPLINE -> "Saaya support point" })
                }
                userClusters.forEach { cluster ->
                    Circle(center = cluster.location, radius = 45.0, fillColor = SaayaSuccessGreen.copy(alpha = 0.13f), strokeColor = SaayaSuccessGreen.copy(alpha = 0.40f), strokeWidth = 1.5f)
                    Marker(state = rememberMarkerState(position = cluster.location), title = "${cluster.userCount} Saaya users", snippet = "Approximate location only")
                }
            }

            /*
             * TOP BAR & RESOURCES LINK
             */
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = SaayaWhite.copy(alpha = 0.96f), shadowElevation = 5.dp) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        SaayaTopBar(onTrustedCircleClick = onTrustedCircleClick, onChatClick = onChatClick)
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(modifier = Modifier.size(30.dp), shape = CircleShape, color = SaayaEmergencyRed.copy(alpha = 0.10f)) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(imageVector = Icons.Outlined.LocationOn, contentDescription = null, tint = SaayaEmergencyRed, modifier = Modifier.size(17.dp))
                                }
                            }
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(text = "SAAYA NETWORK", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = SaayaBlack)
                                Text(text = "Faculty of Technology • 100 m zone", fontSize = 9.sp, color = SaayaTextSecondary)
                            }
                            Surface(onClick = onResourcesClick, shape = RoundedCornerShape(10.dp), color = SaayaBlack) {
                                Row(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Outlined.Gavel, null, tint = SaayaWhite, modifier = Modifier.size(12.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Legal", color = SaayaWhite, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }

            if (showHelplines) {
                OfflineHelplineCard(helplines = offlineHelplines, onClose = { showHelplines = false }, modifier = Modifier.align(Alignment.TopStart).padding(start = 12.dp, top = 128.dp))
            } else {
                Surface(modifier = Modifier.align(Alignment.TopStart).padding(start = 12.dp, top = 128.dp), shape = RoundedCornerShape(12.dp), color = SaayaWhite, shadowElevation = 4.dp, onClick = { showHelplines = true }) {
                    Row(modifier = Modifier.padding(horizontal = 11.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(imageVector = Icons.Outlined.Phone, contentDescription = null, tint = SaayaEmergencyRed, modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(text = "Helplines", fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            Surface(modifier = Modifier.align(Alignment.TopEnd).padding(end = 12.dp, top = 128.dp), shape = RoundedCornerShape(12.dp), color = SaayaWhite.copy(alpha = 0.96f), shadowElevation = 4.dp) {
                Row(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.size(9.dp).background(SaayaEmergencyRed, CircleShape))
                    Spacer(Modifier.width(6.dp))
                    @Suppress("DEPRECATION")
                    Text(text = "100 m community zone", fontSize = 9.sp, color = SaayaTextSecondary, fontWeight = FontWeight.Medium)
                }
            }

            Surface(modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(12.dp), shape = RoundedCornerShape(17.dp), color = SaayaWhite, shadowElevation = 7.dp) {
                Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Surface(modifier = Modifier.size(42.dp), shape = CircleShape, color = SaayaSuccessGreen.copy(alpha = 0.10f)) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(imageVector = Icons.Outlined.People, contentDescription = null, tint = SaayaSuccessGreen, modifier = Modifier.size(21.dp))
                        }
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = "${userClusters.sumOf { it.userCount }} Saaya users nearby", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = SaayaBlack)
                        Spacer(Modifier.height(2.dp))
                        Text(text = "Approximate locations • privacy protected", fontSize = 9.sp, color = SaayaTextSecondary)
                    }
                    Surface(shape = RoundedCornerShape(10.dp), color = SaayaSuccessGreen.copy(alpha = 0.10f)) {
                        Text(text = "CONNECTED", modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp), fontSize = 8.sp, fontWeight = FontWeight.Bold, color = SaayaSuccessGreen)
                    }
                }
            }
        }
    }

    sosError?.let { error ->
        androidx.compose.material3.AlertDialog(onDismissRequest = { sosError = null }, title = { Text("SOS Error") }, text = { Text(error) }, confirmButton = { androidx.compose.material3.TextButton(onClick = { sosError = null }) { Text("OK") } })
    }
}

@Composable
private fun OfflineHelplineCard(helplines: List<OfflineHelpline>, onClose: () -> Unit, modifier: Modifier = Modifier) {
    Surface(modifier = modifier.width(220.dp), shape = RoundedCornerShape(16.dp), color = SaayaWhite.copy(alpha = 0.97f), shadowElevation = 7.dp) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(modifier = Modifier.size(30.dp), shape = CircleShape, color = SaayaEmergencyRed.copy(alpha = 0.10f)) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(imageVector = Icons.Outlined.Call, contentDescription = null, tint = SaayaEmergencyRed, modifier = Modifier.size(16.dp))
                    }
                }
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = "Emergency directory", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = SaayaBlack)
                    Text(text = "Available offline", fontSize = 8.sp, color = SaayaSuccessGreen)
                }
                IconButton(onClick = onClose, modifier = Modifier.size(25.dp)) {
                    Icon(imageVector = Icons.Outlined.Close, contentDescription = "Close", tint = SaayaTextSecondary, modifier = Modifier.size(15.dp))
                }
            }
            Spacer(Modifier.height(8.dp))
            helplines.forEach { helpline ->
                HelplineRow(helpline = helpline)
                Spacer(Modifier.height(6.dp))
            }
        }
    }
}

@Composable
private fun HelplineRow(helpline: OfflineHelpline) {
    Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp), color = SaayaSurface) {
        Row(modifier = Modifier.padding(9.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = helpline.name, fontSize = 9.sp, fontWeight = FontWeight.SemiBold, color = SaayaBlack)
                Text(text = helpline.description, fontSize = 7.sp, color = SaayaTextSecondary)
            }
            Surface(shape = RoundedCornerShape(7.dp), color = SaayaEmergencyRed.copy(alpha = 0.09f)) {
                Text(text = helpline.number, modifier = Modifier.padding(horizontal = 7.dp, vertical = 5.dp), fontSize = 9.sp, fontWeight = FontWeight.Bold, color = SaayaEmergencyRed)
            }
        }
    }
}

@SuppressLint("MissingPermission")
private fun getCurrentLocation(context: Context, onLocation: (Location) -> Unit) {
    val client: FusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(context)
    client.lastLocation.addOnSuccessListener { location ->
        if (location != null) { onLocation(location) }
    }
}
