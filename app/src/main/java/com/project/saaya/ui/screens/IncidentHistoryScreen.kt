package com.project.saaya.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.WifiTethering
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.project.saaya.data.model.Incident
import com.project.saaya.data.repository.LiveSafetyRepository
import com.project.saaya.ui.state.IncidentViewModel
import com.project.saaya.ui.theme.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

private fun incidentDate(value: String): String = runCatching {
    val parser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(requireNotNull(parser.parse(value)))
}.getOrDefault(value)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IncidentHistoryScreen(
    model: IncidentViewModel,
    liveRepository: LiveSafetyRepository,
    userId: String,
    onBack: () -> Unit,
    onChat: (String) -> Unit,
    onReports: (String) -> Unit,
    onRejoin: (String) -> Unit
) {
    val state by model.uiState.collectAsState()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    var refreshTrigger by remember { mutableIntStateOf(0) }
    var liveIncidents by remember(userId) { mutableStateOf(emptyList<Incident>()) }
    var liveError by remember(userId) { mutableStateOf<String?>(null) }
    var rejoiningId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(model, liveRepository, lifecycle, refreshTrigger, userId) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            model.refresh()
            while (true) {
                try {
                    liveIncidents = liveRepository.activeIncidents().filter { incident ->
                        val status = liveRepository.status(incident.id, verifySession = true)
                        status.active && status.sessionActive && status.readiness == "configured"
                    }
                    liveError = null
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    liveIncidents = emptyList()
                    liveError = "Could not verify active live sessions."
                }
                delay(15000)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Incident Logs",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = SaayaBlack
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { refreshTrigger++ }, enabled = !state.isLoading) {
                        Icon(Icons.Outlined.Refresh, "Refresh")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SaayaWhite)
            )
        },
        containerColor = SaayaWhite
    ) { paddingValues ->
        Box(Modifier.fillMaxSize().padding(paddingValues)) {
            if (state.isLoading && state.incidents.isEmpty()) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                if (state.error != null || liveError != null) {
                    item {
                        Surface(
                            color = SaayaEmergencyRed.copy(alpha = 0.1f),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = state.error ?: liveError ?: "",
                                color = SaayaEmergencyRed,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                    }
                }

                if (liveIncidents.isNotEmpty()) {
                    item {
                        SectionHeader("ACTIVE SESSIONS")
                    }
                    items(liveIncidents, key = { "live:${it.id}" }) { incident ->
                        ActiveIncidentCard(
                            incident = incident,
                            isChecking = rejoiningId == incident.id,
                            onRejoin = {
                                rejoiningId = incident.id
                                scope.launch {
                                    try {
                                        val status = liveRepository.status(incident.id, verifySession = true)
                                        if (status.active && status.sessionActive && status.readiness == "configured") {
                                            onRejoin(incident.id)
                                        } else {
                                            liveIncidents = liveIncidents.filterNot { it.id == incident.id }
                                            liveError = "This live session is no longer active."
                                        }
                                    } catch (cancelled: CancellationException) { throw cancelled }
                                    catch (_: Exception) {
                                        liveIncidents = liveIncidents.filterNot { it.id == incident.id }
                                        liveError = "This live session is unavailable."
                                    } finally { rejoiningId = null }
                                }
                            }
                        )
                    }
                }

                val pastIncidents = state.incidents.filterNot { p -> liveIncidents.any { l -> l.id == p.id } }
                
                if (pastIncidents.isNotEmpty()) {
                    item {
                        SectionHeader("PAST INCIDENTS")
                    }
                    items(pastIncidents, key = { it.id }) { incident ->
                        PastIncidentCard(
                            incident = incident,
                            onChat = { onChat(incident.id) },
                            onReports = { onReports(incident.id) }
                        )
                    }
                } else if (!state.isLoading && liveIncidents.isEmpty()) {
                    item {
                        EmptyState()
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        color = SaayaTextSecondary,
        letterSpacing = 1.sp,
        modifier = Modifier.padding(bottom = 4.dp)
    )
}

@Composable
private fun ActiveIncidentCard(
    incident: Incident,
    isChecking: Boolean,
    onRejoin: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = SaayaBlack,
        shadowElevation = 4.dp
    ) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = SaayaEmergencyRed
                ) {
                    Text(
                        "LIVE",
                        color = SaayaWhite,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Black,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    "Ongoing SOS Session",
                    color = SaayaWhite,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            
            Spacer(Modifier.height(16.dp))
            
            Text(
                "Started: ${incidentDate(incident.startedAt)}",
                color = SaayaWhite.copy(alpha = 0.7f),
                fontSize = 13.sp
            )
            Text(
                "Reference ID: ${incident.id.takeLast(8)}",
                color = SaayaWhite.copy(alpha = 0.5f),
                fontSize = 11.sp
            )

            Spacer(Modifier.height(20.dp))

            Button(
                onClick = onRejoin,
                enabled = !isChecking,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = SaayaWhite,
                    contentColor = SaayaBlack
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                if (isChecking) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), color = SaayaBlack, strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Outlined.WifiTethering, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Rejoin Live Session", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun PastIncidentCard(
    incident: Incident,
    onChat: () -> Unit,
    onReports: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = SaayaWhite,
        border = BorderStroke(1.dp, SaayaBorder)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = incidentDate(incident.startedAt),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = SaayaBlack
                    )
                    Text(
                        text = "ID: ${incident.id.takeLast(8)}",
                        fontSize = 11.sp,
                        color = SaayaTextSecondary
                    )
                }
                
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = if (incident.status == "ENDED") SaayaSurface else SaayaEmergencyRed.copy(alpha = 0.1f)
                ) {
                    Text(
                        text = incident.status,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (incident.status == "ENDED") SaayaTextSecondary else SaayaEmergencyRed,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ActionButton(
                    label = "Chat Log",
                    icon = Icons.Outlined.ChatBubbleOutline,
                    onClick = onChat,
                    modifier = Modifier.weight(1f)
                )
                
                if (incident.status == "ENDED") {
                    ActionButton(
                        label = "Reports",
                        icon = Icons.Outlined.Description,
                        onClick = onReports,
                        modifier = Modifier.weight(1f)
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun ActionButton(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(40.dp),
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, SaayaBorder),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
    ) {
        Icon(icon, null, modifier = Modifier.size(16.dp), tint = SaayaBlack)
        Spacer(Modifier.width(8.dp))
        Text(label, fontSize = 12.sp, color = SaayaBlack, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun EmptyState() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 60.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Surface(
            modifier = Modifier.size(64.dp),
            shape = CircleShape,
            color = SaayaSurface
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Outlined.History,
                    null,
                    modifier = Modifier.size(32.dp),
                    tint = SaayaTextSecondary
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(
            "No incidents recorded",
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            color = SaayaBlack
        )
        Text(
            "Your SOS history will appear here.",
            fontSize = 13.sp,
            color = SaayaTextSecondary
        )
    }
}
