package com.project.saaya.ui.screens

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.libraries.places.api.Places
import com.google.android.libraries.places.api.model.AutocompletePrediction
import com.google.android.libraries.places.api.model.AutocompleteSessionToken
import com.google.android.libraries.places.api.model.Place
import com.google.android.libraries.places.api.net.FetchPlaceRequest
import com.google.android.libraries.places.api.net.FindAutocompletePredictionsRequest
import com.google.maps.android.compose.*
import com.project.saaya.data.model.UserProfile
import com.project.saaya.ui.components.HomeTab
import com.project.saaya.ui.components.SaayaBottomNavigationBar
import com.project.saaya.ui.components.SaayaTopBar
import com.project.saaya.ui.components.SmallSosButton
import com.project.saaya.ui.sos.rememberSosCallAction
import com.project.saaya.ui.state.RouteInfo
import com.project.saaya.ui.state.TransportMode
import com.project.saaya.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL


@Composable
fun JourneyScreen(
    userProfile: UserProfile,
    onHomeClick: () -> Unit,
    onNetworkClick: () -> Unit,
    onActivityClick: () -> Unit,
    onProfileClick: () -> Unit,
    onTrustedCircleClick: () -> Unit,
    onChatClick: () -> Unit
) {
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        initializePlaces(context)
    }

    var showJourneySetup by rememberSaveable {
        mutableStateOf(false)
    }

    var journeyActive by rememberSaveable {
        mutableStateOf(false)
    }

    var journeyDestination by rememberSaveable {
        mutableStateOf("")
    }

    var journeyStartLocation by rememberSaveable {
        mutableStateOf("")
    }

    var startLat by rememberSaveable {
        mutableStateOf<Double?>(null)
    }

    var startLng by rememberSaveable {
        mutableStateOf<Double?>(null)
    }

    var destinationLat by rememberSaveable {
        mutableStateOf<Double?>(null)
    }

    var destinationLng by rememberSaveable {
        mutableStateOf<Double?>(null)
    }

    var selectedTransportName by rememberSaveable {
        mutableStateOf(TransportMode.WALKING.name)
    }

    var vehicleNumber by rememberSaveable {
        mutableStateOf("")
    }

    var activeRoute by remember {
        mutableStateOf<RouteInfo?>(null)
    }

    var liveLocation by remember {
        mutableStateOf<Location?>(null)
    }

    var sosError by rememberSaveable {
        mutableStateOf<String?>(null)
    }

    val callSosContact = rememberSosCallAction(
        phoneNumber = userProfile.sosContactNumber,
        onError = {
            sosError = it
        }
    )

    val notificationPermissionLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { }

    LaunchedEffect(
        journeyActive,
        destinationLat,
        destinationLng,
        selectedTransportName
    ) {
        while (
            journeyActive &&
            destinationLat != null &&
            destinationLng != null
        ) {
            if (
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                getCurrentLocation(context) { location ->
                    liveLocation = location
                }

                val location = liveLocation

                if (location != null) {
                    activeRoute = computeRoute(
                        context = context,
                        origin = LatLng(
                            location.latitude,
                            location.longitude
                        ),
                        destination = LatLng(
                            destinationLat!!,
                            destinationLng!!
                        ),
                        transport = TransportMode.valueOf(
                            selectedTransportName
                        )
                    ) ?: activeRoute
                }
            }

            delay(30_000L)
        }
    }

    Scaffold(
        containerColor = SaayaWhite,

        bottomBar = {
            if (!showJourneySetup) {
                SaayaBottomNavigationBar(
                    selectedTab = HomeTab.Journey,

                    onTabSelected = { tab ->
                        when (tab) {
                            HomeTab.Home -> onHomeClick()
                            HomeTab.Journey -> Unit
                            HomeTab.Network -> onNetworkClick()
                            HomeTab.Activity -> onActivityClick()
                            HomeTab.Profile -> onProfileClick()
                        }
                    }
                )
            }
        },

        floatingActionButton = {
            if (!showJourneySetup) {
                SmallSosButton(
                    onClick = {
                        if (userProfile.sosContactNumber.isBlank()) {
                            onProfileClick()
                        } else {
                            callSosContact()
                        }
                    }
                )
            }
        },

        floatingActionButtonPosition = FabPosition.Start

    ) { innerPadding ->

        if (showJourneySetup) {

            JourneySetupScreen(
                contentPadding = innerPadding,

                initialStartLocation = journeyStartLocation,
                initialStartLat = startLat,
                initialStartLng = startLng,

                initialDestination = journeyDestination,
                initialDestinationLat = destinationLat,
                initialDestinationLng = destinationLng,

                selectedTransport =
                    TransportMode.valueOf(
                        selectedTransportName
                    ),

                vehicleNumber = vehicleNumber,

                onStartLocationSelected = {
                        name,
                        lat,
                        lng ->

                    journeyStartLocation = name
                    startLat = lat
                    startLng = lng
                },

                onDestinationSelected = {
                        name,
                        lat,
                        lng ->

                    journeyDestination = name
                    destinationLat = lat
                    destinationLng = lng
                },

                onTransportSelected = {
                    selectedTransportName = it.name
                },

                onVehicleNumberChange = {
                    vehicleNumber = it
                },

                onBack = {
                    showJourneySetup = false
                },

                onStartJourney = { route ->

                    activeRoute = route
                    journeyActive = true
                    showJourneySetup = false

                    JourneyCheckInScheduler.scheduleMidJourneyCheckIn(
                        context,
                        route?.durationSeconds ?: 0L
                    )

                    if (
                        android.os.Build.VERSION.SDK_INT >=
                        android.os.Build.VERSION_CODES.TIRAMISU
                    ) {
                        notificationPermissionLauncher.launch(
                            Manifest.permission.POST_NOTIFICATIONS
                        )
                    }
                }
            )

        } else {

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(
                        horizontal = 18.dp,
                        vertical = 12.dp
                    )
                    .verticalScroll(
                        rememberScrollState()
                    )
            ) {

                SaayaTopBar(
                    onTrustedCircleClick = onTrustedCircleClick,
                    onChatClick = onChatClick
                )

                Spacer(
                    Modifier.height(20.dp)
                )

                Text(
                    text = "JOURNEY",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = SaayaBlack
                )

                Spacer(
                    Modifier.height(20.dp)
                )

                if (journeyActive) {

                    ActiveJourneyCard(
                        destination = journeyDestination,
                        transportMode =
                            TransportMode.valueOf(
                                selectedTransportName
                            ),
                        vehicleNumber = vehicleNumber,
                        route = activeRoute,

                        onStopJourney = {

                            JourneyCheckInScheduler
                                .cancelMidJourneyCheckIn(
                                    context
                                )

                            journeyActive = false
                            journeyDestination = ""
                            journeyStartLocation = ""

                            startLat = null
                            startLng = null

                            destinationLat = null
                            destinationLng = null

                            vehicleNumber = ""
                            activeRoute = null

                            selectedTransportName =
                                TransportMode.WALKING.name
                        },

                        onShareJourney = {

                            if (activeRoute != null) {

                                val minutes =
                                    (
                                            (activeRoute?.durationSeconds ?: 0) +
                                                    59
                                            ) / 60

                                JourneyShareHelper.shareJourney(
                                    context = context,
                                    startLocation = journeyStartLocation,
                                    destination = journeyDestination,
                                    transportMode =
                                        TransportMode.valueOf(
                                            selectedTransportName
                                        ).title,
                                    etaMinutes = minutes.toInt(),
                                    distanceKm =
                                        (activeRoute?.distanceMeters ?: 0) /
                                                1000.0,
                                    latitude = startLat,
                                    longitude = startLng
                                )
                            }
                        }
                    )

                } else {

                    StartJourneyCard(
                        onStartJourney = {
                            showJourneySetup = true
                        }
                    )

                    Spacer(
                        Modifier.height(28.dp)
                    )

                    JourneyHistorySection()
                }
            }
        }
    }

    sosError?.let { error ->

        AlertDialog(
            onDismissRequest = {
                sosError = null
            },

            title = {
                Text("SOS Error")
            },

            text = {
                Text(error)
            },

            confirmButton = {

                TextButton(
                    onClick = {
                        sosError = null
                    }
                ) {
                    Text("OK")
                }
            }
        )
    }
}


private fun initializePlaces(
    context: Context
) {
    if (Places.isInitialized()) return

    try {

        val appInfo =
            context.packageManager.getApplicationInfo(
                context.packageName,
                PackageManager.GET_META_DATA
            )

        val apiKey =
            appInfo.metaData?.getString(
                "com.google.android.geo.API_KEY"
            )

        if (apiKey.isNullOrBlank()) return

        try {

            Places.initializeWithNewPlacesApiEnabled(
                context.applicationContext,
                apiKey
            )

        } catch (e: Exception) {

            Places.initialize(
                context.applicationContext,
                apiKey
            )
        }

    } catch (e: Exception) {

        Log.e(
            "SAAYA_PLACES",
            "Initialization failed",
            e
        )
    }
}


@Composable
private fun StartJourneyCard(
    onStartJourney: () -> Unit
) {

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = SaayaBlack
    ) {

        Column(
            modifier = Modifier.padding(18.dp)
        ) {

            Text(
                "GOING SOMEWHERE?",
                color = Color.LightGray,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(
                Modifier.height(6.dp)
            )

            Text(
                "Start a protected journey.",
                color = SaayaWhite,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold
            )

            Spacer(
                Modifier.height(5.dp)
            )

            Text(
                "GPS tracking • ETA monitoring • Check-ins",
                color = Color.LightGray,
                fontSize = 11.sp
            )

            Spacer(
                Modifier.height(15.dp)
            )

            Button(
                onClick = onStartJourney,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(11.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = SaayaWhite,
                    contentColor = SaayaBlack
                )
            ) {

                Text(
                    "Start a Journey",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )

                Spacer(
                    Modifier.width(7.dp)
                )

                Icon(
                    Icons.AutoMirrored.Outlined.ArrowForward,
                    contentDescription = null
                )
            }
        }
    }
}


@Composable
private fun JourneyHistorySection() {

    Column(
        modifier = Modifier.fillMaxWidth()
    ) {

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {

            Column(
                modifier = Modifier.weight(1f)
            ) {

                Text(
                    text = "JOURNEY HISTORY",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = SaayaBlack
                )

                Spacer(
                    Modifier.height(3.dp)
                )

                Text(
                    text = "Your recent protected journeys",
                    fontSize = 9.sp,
                    color = SaayaTextSecondary
                )
            }

            Text(
                text = "View all",
                fontSize = 9.sp,
                fontWeight = FontWeight.SemiBold,
                color = SaayaTextSecondary
            )
        }

        Spacer(
            Modifier.height(12.dp)
        )

        JourneyHistoryCard(
            date = "01 SEP",
            time = "6:42 PM",
            from = "Faculty of Technology, DU",
            to = "Kamla Nagar",
            duration = "24 min",
            distance = "3.8 km",
            mode = "Walking",
            status = "Completed"
        )

        Spacer(
            Modifier.height(10.dp)
        )

        JourneyHistoryCard(
            date = "31 AUG",
            time = "5:18 PM",
            from = "North Campus",
            to = "GTB Nagar Metro",
            duration = "18 min",
            distance = "2.6 km",
            mode = "Walking",
            status = "Completed"
        )

        Spacer(
            Modifier.height(10.dp)
        )

        JourneyHistoryCard(
            date = "29 AUG",
            time = "8:05 PM",
            from = "Faculty of Technology, DU",
            to = "Vijay Nagar",
            duration = "31 min",
            distance = "5.1 km",
            mode = "Cab",
            status = "Completed"
        )

        Spacer(
            Modifier.height(10.dp)
        )

        JourneyHistoryCard(
            date = "27 AUG",
            time = "7:24 PM",
            from = "Kamla Nehru College",
            to = "Green Park Metro",
            duration = "22 min",
            distance = "4.2 km",
            mode = "Two Wheeler",
            status = "Completed"
        )
    }
}


@Composable
private fun JourneyHistoryCard(
    date: String,
    time: String,
    from: String,
    to: String,
    duration: String,
    distance: String,
    mode: String,
    status: String
) {

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                // Future: open journey details
            },

        shape = RoundedCornerShape(16.dp),

        color = SaayaWhite,

        border = BorderStroke(
            1.dp,
            SaayaBorder
        ),

        shadowElevation = 2.dp
    ) {

        Column(
            modifier = Modifier.padding(14.dp)
        ) {

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {

                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = SaayaSurface
                ) {

                    Column(
                        modifier = Modifier
                            .width(52.dp)
                            .padding(
                                vertical = 7.dp
                            ),

                        horizontalAlignment =
                            Alignment.CenterHorizontally
                    ) {

                        Text(
                            text = date,
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Bold,
                            color = SaayaBlack
                        )

                        Spacer(
                            Modifier.height(2.dp)
                        )

                        Text(
                            text = time,
                            fontSize = 7.sp,
                            color = SaayaTextSecondary
                        )
                    }
                }

                Spacer(
                    Modifier.width(11.dp)
                )

                Column(
                    modifier = Modifier.weight(1f)
                ) {

                    Text(
                        text = "Protected journey",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = SaayaBlack
                    )

                    Spacer(
                        Modifier.height(3.dp)
                    )

                    Text(
                        text = "$from → $to",
                        fontSize = 9.sp,
                        color = SaayaTextSecondary
                    )
                }

                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = Color(0xFFF0F5F1)
                ) {

                    Text(
                        text = status,
                        fontSize = 7.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF55735E),
                        modifier = Modifier.padding(
                            horizontal = 8.dp,
                            vertical = 5.dp
                        )
                    )
                }
            }

            Spacer(
                Modifier.height(13.dp)
            )

            HorizontalDivider(
                color = SaayaBorder,
                thickness = 0.7.dp
            )

            Spacer(
                Modifier.height(11.dp)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement =
                    Arrangement.SpaceBetween
            ) {

                HistoryMetric(
                    label = "DURATION",
                    value = duration
                )

                HistoryMetric(
                    label = "DISTANCE",
                    value = distance
                )

                HistoryMetric(
                    label = "MODE",
                    value = mode
                )
            }
        }
    }
}


@Composable
private fun HistoryMetric(
    label: String,
    value: String
) {

    Column {

        Text(
            text = label,
            fontSize = 7.sp,
            fontWeight = FontWeight.Bold,
            color = SaayaTextSecondary
        )

        Spacer(
            Modifier.height(3.dp)
        )

        Text(
            text = value,
            fontSize = 9.sp,
            fontWeight = FontWeight.SemiBold,
            color = SaayaBlack
        )
    }
}


@Composable
private fun ActiveJourneyCard(
    destination: String,
    transportMode: TransportMode,
    vehicleNumber: String,
    route: RouteInfo?,
    onStopJourney: () -> Unit,
    onShareJourney: () -> Unit
) {

    Column {

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {

            Column(
                modifier = Modifier.weight(1f)
            ) {

                Text(
                    "JOURNEY ACTIVE",
                    color = SaayaSuccessGreen,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold
                )

                Text(
                    destination.ifBlank {
                        "Your destination"
                    },
                    color = SaayaBlack,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Surface(
                shape = RoundedCornerShape(20.dp),
                color = SaayaSuccessGreen.copy(
                    alpha = 0.1f
                )
            ) {

                Text(
                    "● LIVE",
                    color = SaayaSuccessGreen,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(
                        horizontal = 10.dp,
                        vertical = 6.dp
                    )
                )
            }
        }

        Spacer(
            Modifier.height(12.dp)
        )

        JourneyMap(
            route = route,
            modifier = Modifier
                .fillMaxWidth()
                .height(215.dp)
        )

        Spacer(
            Modifier.height(10.dp)
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {

            JourneyMetric(
                Modifier.weight(1f),
                "ETA",
                route?.etaText ?: "—"
            )

            JourneyMetric(
                Modifier.weight(1f),
                "DISTANCE",
                route?.distanceText ?: "—"
            )

            JourneyMetric(
                Modifier.weight(1f),
                "MODE",
                transportMode.title
            )
        }

        if (
            vehicleNumber.isNotBlank() &&
            transportMode != TransportMode.WALKING
        ) {

            Spacer(
                Modifier.height(8.dp)
            )

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                color = SaayaSurface
            ) {

                Row(
                    modifier = Modifier.padding(11.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {

                    Icon(
                        Icons.Outlined.DirectionsCar,
                        contentDescription = null,
                        tint = SaayaBlack,
                        modifier = Modifier.size(17.dp)
                    )

                    Spacer(
                        Modifier.width(7.dp)
                    )

                    Text(
                        vehicleNumber,
                        color = SaayaBlack,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        Spacer(
            Modifier.height(16.dp)
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {

            Button(
                onClick = onShareJourney,
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp),
                shape = RoundedCornerShape(11.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = SaayaBlack,
                    contentColor = SaayaWhite
                )
            ) {

                Icon(
                    Icons.Outlined.Share,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )

                Spacer(
                    Modifier.width(8.dp)
                )

                Text(
                    "Share",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Button(
                onClick = onStopJourney,
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp),
                shape = RoundedCornerShape(11.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = SaayaBlack
                ),
                border = BorderStroke(
                    1.dp,
                    SaayaBlack
                )
            ) {

                Text(
                    "Stop Journey",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}


@Composable
private fun JourneyMetric(
    modifier: Modifier,
    title: String,
    value: String
) {

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(11.dp),
        color = SaayaSurface
    ) {

        Column(
            modifier = Modifier.padding(10.dp)
        ) {

            Text(
                title,
                color = SaayaTextSecondary,
                fontSize = 8.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(
                Modifier.height(3.dp)
            )

            Text(
                value,
                color = SaayaBlack,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}


@Composable
private fun JourneyMap(
    route: RouteInfo?,
    modifier: Modifier
) {

    val cameraPositionState =
        rememberCameraPositionState()

    val routePoints =
        route?.polyline ?: emptyList()

    val startMarkerState =
        rememberMarkerState()

    val destinationMarkerState =
        rememberMarkerState()

    LaunchedEffect(routePoints) {

        if (routePoints.isNotEmpty()) {

            cameraPositionState.animate(
                CameraUpdateFactory.newLatLngZoom(
                    routePoints[routePoints.size / 2],
                    13f
                )
            )

            startMarkerState.position =
                routePoints.first()

            destinationMarkerState.position =
                routePoints.last()
        }
    }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = SaayaSurface
    ) {

        GoogleMap(
            modifier = Modifier.fillMaxSize(),
            cameraPositionState = cameraPositionState,

            uiSettings = MapUiSettings(
                zoomControlsEnabled = false,
                compassEnabled = false,
                mapToolbarEnabled = false
            )
        ) {

            if (routePoints.isNotEmpty()) {

                Polyline(
                    points = routePoints,
                    width = 9f,
                    color = SaayaBlack
                )

                Marker(
                    state = startMarkerState,
                    title = "Start"
                )

                Marker(
                    state = destinationMarkerState,
                    title = "Destination"
                )
            }
        }
    }
}


@Composable
private fun JourneySetupScreen(
    contentPadding: PaddingValues,
    initialStartLocation: String,
    initialStartLat: Double?,
    initialStartLng: Double?,
    initialDestination: String,
    initialDestinationLat: Double?,
    initialDestinationLng: Double?,
    selectedTransport: TransportMode,
    vehicleNumber: String,
    onStartLocationSelected: (String, Double, Double) -> Unit,
    onDestinationSelected: (String, Double, Double) -> Unit,
    onTransportSelected: (TransportMode) -> Unit,
    onVehicleNumberChange: (String) -> Unit,
    onBack: () -> Unit,
    onStartJourney: (RouteInfo?) -> Unit
) {

    val context = LocalContext.current

    var startLocationText by rememberSaveable {
        mutableStateOf(initialStartLocation)
    }

    var destinationText by rememberSaveable {
        mutableStateOf(initialDestination)
    }

    var startLat by rememberSaveable {
        mutableStateOf(initialStartLat)
    }

    var startLng by rememberSaveable {
        mutableStateOf(initialStartLng)
    }

    var destinationLat by rememberSaveable {
        mutableStateOf(initialDestinationLat)
    }

    var destinationLng by rememberSaveable {
        mutableStateOf(initialDestinationLng)
    }

    var route by remember {
        mutableStateOf<RouteInfo?>(null)
    }

    var routeLoading by remember {
        mutableStateOf(false)
    }

    var currentDeviceLocation by remember {
        mutableStateOf<Location?>(null)
    }

    val locationPermissionLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { result ->

            if (
                result[
                    Manifest.permission.ACCESS_FINE_LOCATION
                ] == true
            ) {

                getCurrentLocation(context) { location ->

                    currentDeviceLocation = location

                    if (startLat == null) {

                        startLat = location.latitude
                        startLng = location.longitude
                        startLocationText = ""
                    }
                }
            }
        }

    LaunchedEffect(Unit) {

        if (
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {

            getCurrentLocation(context) { location ->

                currentDeviceLocation = location

                if (startLat == null) {

                    startLat = location.latitude
                    startLng = location.longitude
                    startLocationText = ""
                }
            }

        } else {

            locationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    LaunchedEffect(
        startLat,
        startLng,
        destinationLat,
        destinationLng,
        selectedTransport
    ) {

        if (
            startLat != null &&
            startLng != null &&
            destinationLat != null &&
            destinationLng != null
        ) {

            routeLoading = true

            route = computeRoute(
                context,
                LatLng(
                    startLat!!,
                    startLng!!
                ),
                LatLng(
                    destinationLat!!,
                    destinationLng!!
                ),
                selectedTransport
            )

            routeLoading = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SaayaWhite)
            .padding(contentPadding)
            .padding(
                horizontal = 18.dp,
                vertical = 8.dp
            )
            .verticalScroll(
                rememberScrollState()
            )
    ) {

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {

            IconButton(
                onClick = onBack
            ) {

                Icon(
                    Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = "Back"
                )
            }

            Column {

                Text(
                    "Plan your journey",
                    color = SaayaBlack,
                    fontSize = 21.sp,
                    fontWeight = FontWeight.SemiBold
                )

                Text(
                    "Saaya will keep you connected.",
                    color = SaayaTextSecondary,
                    fontSize = 11.sp
                )
            }
        }

        Spacer(
            Modifier.height(24.dp)
        )

        PlaceSearchField(
            "Starting point",
            startLocationText,
            "Current location",
            true,
            currentDeviceLocation,

            onTextChanged = {
                startLocationText = it
                startLat = null
                startLng = null
            },

            onPlaceSelected = { prediction ->

                fetchPlaceLocation(
                    context,
                    prediction.placeId
                ) { name, lat, lng ->

                    startLocationText = name
                    startLat = lat
                    startLng = lng

                    onStartLocationSelected(
                        name,
                        lat,
                        lng
                    )
                }
            },

            onCurrentLocationSelected = {

                currentDeviceLocation?.let {

                    startLocationText = ""
                    startLat = it.latitude
                    startLng = it.longitude

                    onStartLocationSelected(
                        "Current location",
                        it.latitude,
                        it.longitude
                    )
                }
            }
        )

        Spacer(
            Modifier.height(9.dp)
        )

        PlaceSearchField(
            "Destination",
            destinationText,
            "Search a place",
            false,
            currentDeviceLocation,

            onTextChanged = {
                destinationText = it
                destinationLat = null
                destinationLng = null
                route = null
            },

            onPlaceSelected = { prediction ->

                fetchPlaceLocation(
                    context,
                    prediction.placeId
                ) { name, lat, lng ->

                    destinationText = name
                    destinationLat = lat
                    destinationLng = lng

                    onDestinationSelected(
                        name,
                        lat,
                        lng
                    )
                }
            },

            onCurrentLocationSelected = null
        )

        if (
            startLat != null &&
            destinationLat != null
        ) {

            Spacer(
                Modifier.height(14.dp)
            )

            RoutePreviewCard(
                route,
                routeLoading
            )
        }

        Spacer(
            Modifier.height(23.dp)
        )

        TransportSelector(
            selectedTransport,
            onTransportSelected
        )

        if (
            selectedTransport in listOf(
                TransportMode.CAB,
                TransportMode.TWO_WHEELER,
                TransportMode.CAR
            )
        ) {

            Spacer(
                Modifier.height(23.dp)
            )

            OutlinedTextField(
                value = vehicleNumber,
                onValueChange = onVehicleNumberChange,
                modifier = Modifier.fillMaxWidth(),
                label = {
                    Text("Vehicle number")
                },
                shape = RoundedCornerShape(12.dp)
            )
        }

        Spacer(
            Modifier.height(22.dp)
        )

        Button(
            onClick = {
                onStartJourney(route)
            },

            enabled =
                destinationLat != null &&
                        startLat != null &&
                        !routeLoading,

            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),

            shape = RoundedCornerShape(12.dp),

            colors = ButtonDefaults.buttonColors(
                containerColor = SaayaBlack,
                contentColor = SaayaWhite
            )
        ) {

            Text(
                if (routeLoading)
                    "Calculating..."
                else
                    "Start Protected Journey",

                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            )
        }
    }
}


@Composable
private fun TransportSelector(
    selectedMode: TransportMode,
    onSelected: (TransportMode) -> Unit
) {

    Column(
        verticalArrangement = Arrangement.spacedBy(9.dp)
    ) {

        TransportMode.entries
            .chunked(2)
            .forEach { pair ->

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement =
                        Arrangement.spacedBy(9.dp)
                ) {

                    pair.forEach { mode ->

                        TransportOption(
                            Modifier.weight(1f),
                            mode,
                            selectedMode == mode,
                            onSelected
                        )
                    }
                }
            }
    }
}


@Composable
private fun TransportOption(
    modifier: Modifier,
    mode: TransportMode,
    selected: Boolean,
    onClick: (TransportMode) -> Unit
) {

    Surface(
        modifier = modifier.height(73.dp),
        shape = RoundedCornerShape(12.dp),

        color =
            if (selected)
                SaayaBlack
            else
                SaayaWhite,

        border =
            if (selected)
                null
            else
                BorderStroke(
                    1.dp,
                    SaayaBorder
                ),

        onClick = {
            onClick(mode)
        }
    ) {

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {

            Icon(
                mode.icon,
                contentDescription = mode.title,

                tint =
                    if (selected)
                        SaayaWhite
                    else
                        SaayaBlack,

                modifier = Modifier.size(21.dp)
            )

            Text(
                mode.title,

                color =
                    if (selected)
                        SaayaWhite
                    else
                        SaayaBlack,

                fontSize = 9.sp
            )
        }
    }
}


@Composable
private fun RoutePreviewCard(
    route: RouteInfo?,
    loading: Boolean
) {

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = SaayaSurface
    ) {

        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {

            Icon(
                Icons.Outlined.LocationOn,
                contentDescription = null,
                tint = SaayaBlack
            )

            Spacer(
                Modifier.width(10.dp)
            )

            Column {

                Text(
                    "ROUTE PREVIEW",
                    color = SaayaTextSecondary,
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Bold
                )

                if (loading) {

                    Text(
                        "Calculating...",
                        fontSize = 12.sp
                    )

                } else if (route != null) {

                    Text(
                        "${route.etaText} • ${route.distanceText}",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}


@Composable
private fun PlaceSearchField(
    label: String,
    value: String,
    placeholder: String,
    useCurrentLocation: Boolean,
    currentLocation: Location?,
    onTextChanged: (String) -> Unit,
    onPlaceSelected: (AutocompletePrediction) -> Unit,
    onCurrentLocationSelected: (() -> Unit)?
) {

    val context = LocalContext.current

    var predictions by remember {
        mutableStateOf(
            emptyList<AutocompletePrediction>()
        )
    }

    var showPredictions by remember {
        mutableStateOf(false)
    }

    val sessionToken = remember {
        AutocompleteSessionToken.newInstance()
    }

    LaunchedEffect(value) {

        if (value.length < 2) {

            predictions = emptyList()
            showPredictions = false

            return@LaunchedEffect
        }

        if (!Places.isInitialized()) {
            return@LaunchedEffect
        }

        val placesClient =
            Places.createClient(context)

        val requestBuilder =
            FindAutocompletePredictionsRequest
                .builder()
                .setQuery(value)
                .setCountries(listOf("IN"))
                .setSessionToken(sessionToken)

        currentLocation?.let {

            requestBuilder.setOrigin(
                LatLng(
                    it.latitude,
                    it.longitude
                )
            )
        }

        placesClient
            .findAutocompletePredictions(
                requestBuilder.build()
            )
            .addOnSuccessListener { response ->

                predictions =
                    response.autocompletePredictions
                        .take(5)

                showPredictions =
                    predictions.isNotEmpty()
            }
    }

    Column {

        OutlinedTextField(
            value = value,
            onValueChange = onTextChanged,
            modifier = Modifier.fillMaxWidth(),

            label = {
                Text(label)
            },

            placeholder = {
                Text(placeholder)
            },

            leadingIcon = {

                Icon(
                    if (useCurrentLocation)
                        Icons.Outlined.LocationOn
                    else
                        Icons.Outlined.Search,
                    null
                )
            },

            trailingIcon = {

                if (value.isNotBlank()) {

                    IconButton(
                        onClick = {
                            onTextChanged("")
                        }
                    ) {

                        Icon(
                            Icons.Outlined.Close,
                            null,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            },

            shape = RoundedCornerShape(12.dp)
        )

        if (
            useCurrentLocation &&
            value.isBlank() &&
            onCurrentLocationSelected != null
        ) {

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
                    .clickable {
                        onCurrentLocationSelected()
                    },

                color = SaayaSurface,
                shape = RoundedCornerShape(10.dp)
            ) {

                Row(
                    modifier = Modifier.padding(13.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {

                    Icon(
                        Icons.Outlined.LocationOn,
                        null,
                        tint = SaayaBlack,
                        modifier = Modifier.size(18.dp)
                    )

                    Spacer(
                        Modifier.width(10.dp)
                    )

                    Text(
                        "Current location",
                        color = SaayaBlack,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        if (
            showPredictions &&
            predictions.isNotEmpty()
        ) {

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),

                color = SaayaWhite,
                shape = RoundedCornerShape(12.dp),
                shadowElevation = 5.dp,

                border = BorderStroke(
                    1.dp,
                    SaayaBorder
                )
            ) {

                Column {

                    predictions.forEach { prediction ->

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    showPredictions = false
                                    onPlaceSelected(
                                        prediction
                                    )
                                }
                                .padding(13.dp)
                        ) {

                            Text(
                                prediction
                                    .getPrimaryText(null)
                                    .toString(),

                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
        }
    }
}


private fun fetchPlaceLocation(
    context: Context,
    placeId: String,
    onResult: (String, Double, Double) -> Unit
) {

    if (!Places.isInitialized()) return

    val placesClient =
        Places.createClient(context)

    val request =
        FetchPlaceRequest
            .builder(
                placeId,
                listOf(
                    Place.Field.DISPLAY_NAME,
                    Place.Field.LOCATION
                )
            )
            .build()

    placesClient
        .fetchPlace(request)
        .addOnSuccessListener { response ->

            val place = response.place

            place.location?.let {

                onResult(
                    place.displayName
                        ?: "Selected location",

                    it.latitude,
                    it.longitude
                )
            }
        }
}


private suspend fun computeRoute(
    context: Context,
    origin: LatLng,
    destination: LatLng,
    transport: TransportMode
): RouteInfo? = withContext(Dispatchers.IO) {

    try {

        val appInfo =
            context.packageManager.getApplicationInfo(
                context.packageName,
                PackageManager.GET_META_DATA
            )

        val apiKey =
            appInfo.metaData?.getString(
                "com.google.android.geo.API_KEY"
            ) ?: return@withContext null

        val url =
            URL(
                "https://routes.googleapis.com/directions/v2:computeRoutes"
            )

        val connection =
            url.openConnection() as HttpURLConnection

        connection.requestMethod = "POST"
        connection.doOutput = true

        connection.setRequestProperty(
            "Content-Type",
            "application/json"
        )

        connection.setRequestProperty(
            "X-Goog-Api-Key",
            apiKey
        )

        connection.setRequestProperty(
            "X-Goog-FieldMask",
            "routes.duration,routes.distanceMeters,routes.polyline.encodedPolyline"
        )

        val requestBody =
            JSONObject().apply {

                put(
                    "origin",
                    JSONObject().apply {

                        put(
                            "location",
                            JSONObject().apply {

                                put(
                                    "latLng",
                                    JSONObject().apply {

                                        put(
                                            "latitude",
                                            origin.latitude
                                        )

                                        put(
                                            "longitude",
                                            origin.longitude
                                        )
                                    }
                                )
                            }
                        )
                    }
                )

                put(
                    "destination",
                    JSONObject().apply {

                        put(
                            "location",
                            JSONObject().apply {

                                put(
                                    "latLng",
                                    JSONObject().apply {

                                        put(
                                            "latitude",
                                            destination.latitude
                                        )

                                        put(
                                            "longitude",
                                            destination.longitude
                                        )
                                    }
                                )
                            }
                        )
                    }
                )

                put(
                    "travelMode",
                    transport.routesMode
                )
            }

        connection.outputStream.use {
            it.write(
                requestBody
                    .toString()
                    .toByteArray()
            )
        }

        if (
            connection.responseCode !in 200..299
        ) {
            return@withContext null
        }

        val json =
            JSONObject(
                connection.inputStream
                    .bufferedReader()
                    .use {
                        it.readText()
                    }
            )

        val route =
            json.optJSONArray("routes")
                ?.getJSONObject(0)
                ?: return@withContext null

        val points =
            decodePolyline(
                route
                    .optJSONObject("polyline")
                    ?.optString(
                        "encodedPolyline",
                        ""
                    )
                    ?: ""
            )

        RouteInfo(
            route
                .getString("duration")
                .removeSuffix("s")
                .toDouble()
                .toLong(),

            route.getInt(
                "distanceMeters"
            ),

            points
        )

    } catch (e: Exception) {
        null
    }
}


private fun decodePolyline(
    encoded: String
): List<LatLng> {

    val poly = ArrayList<LatLng>()

    var index = 0
    var lat = 0
    var lng = 0

    while (index < encoded.length) {

        var shift = 0
        var result = 0

        do {

            val b =
                encoded[index++].code - 63

            result =
                result or
                        ((b and 0x1f) shl shift)

            shift += 5

        } while (b >= 0x20)

        lat +=
            if (result and 1 != 0)
                (result shr 1).inv()
            else
                result shr 1

        shift = 0
        result = 0

        do {

            val b =
                encoded[index++].code - 63

            result =
                result or
                        ((b and 0x1f) shl shift)

            shift += 5

        } while (b >= 0x20)

        lng +=
            if (result and 1 != 0)
                (result shr 1).inv()
            else
                result shr 1

        poly.add(
            LatLng(
                lat / 1E5,
                lng / 1E5
            )
        )
    }

    return poly
}


@SuppressLint("MissingPermission")
private fun getCurrentLocation(
    context: Context,
    onLocation: (Location) -> Unit
) {

    val client =
        LocationServices
            .getFusedLocationProviderClient(context)

    client.lastLocation.addOnSuccessListener {

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

            client.requestLocationUpdates(

                request,

                object : LocationCallback() {

                    override fun onLocationResult(
                        res: LocationResult
                    ) {

                        res.lastLocation?.let(
                            onLocation
                        )

                        client.removeLocationUpdates(
                            this
                        )
                    }
                },

                context.mainLooper
            )
        }
    }
}