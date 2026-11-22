package com.project.saaya.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.People
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
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.project.saaya.live.LiveKitMediaClient
import com.project.saaya.notifications.SosNotifications
import com.project.saaya.ui.sos.LocalLiveSafetyViewModel
import com.project.saaya.ui.state.connectedViewers
import com.project.saaya.ui.theme.SaayaEmergencyRed
import com.project.saaya.ui.theme.SaayaWhite
import io.livekit.android.renderer.SurfaceViewRenderer
import io.livekit.android.room.Room
import io.livekit.android.room.track.VideoTrack

@Composable
fun LiveSafetyScreen(
    incidentId: String,
    userId: String,
    onBack: () -> Unit,
    onEndIncident: () -> Unit,
    onEvidence: () -> Unit = {},
    rejoinOnly: Boolean = false
) {
    val context = LocalContext.current
    val model = requireNotNull(LocalLiveSafetyViewModel.current)

    val state by model.uiState.collectAsState()
    val media = model.media as LiveKitMediaClient
    val room by media.room.collectAsState()
    val video by media.video.collectAsState()
    val connection by media.connection.collectAsState()
    val connected by media.participants.collectAsState()
    val viewers = connectedViewers(connected, state.viewers)

    var wantsJoin by rememberSaveable(incidentId) { mutableStateOf(true) }
    var showChat by rememberSaveable { mutableStateOf(false) }
    var showParticipants by rememberSaveable(incidentId) { mutableStateOf(false) }
    var microphonePermissionPending by remember(incidentId) { mutableStateOf(false) }
    var retryTrigger by remember { mutableStateOf(0) }

    var permissionVersion by remember { mutableStateOf(0) }

    fun permissionsGranted() = listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO).all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permissionVersion++
    }

    val microphonePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        microphonePermissionPending = false
        model.setViewerMicrophoneEnabled(true, granted)
    }

    LaunchedEffect(connected, state.joined, state.role) {
        if (state.joined && state.role == "publisher") model.refreshViewers()
    }

    // Clear notification for this incident as soon as we enter
    LaunchedEffect(incidentId) {
        SosNotifications.clear(context, incidentId)
    }

    LaunchedEffect(wantsJoin, permissionVersion, incidentId, retryTrigger, rejoinOnly) {
        if (wantsJoin) {
            model.join(incidentId, permissionsGranted(), rejoinOnly)
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        // Full screen video
        if (state.joined && room != null && video != null) {
            LiveSafetyVideo(room!!, video!!, modifier = Modifier.fillMaxSize())
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (state.loading) {
                        CircularProgressIndicator(color = SaayaWhite)
                        Text("Connecting emergency call...", color = SaayaWhite, modifier = Modifier.padding(top = 16.dp))
                    } else if (state.ended) {
                        Text("Emergency call ended.", color = SaayaWhite, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        TextButton(onClick = onBack, modifier = Modifier.padding(top = 16.dp)) {
                            Text("Back to Home", color = SaayaWhite)
                        }
                    } else if (state.error != null) {
                        Text(
                            text = state.error!!,
                            color = SaayaEmergencyRed,
                            modifier = Modifier.padding(16.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                        TextButton(onClick = { retryTrigger++ }) {
                            Text("Retry Connection", color = SaayaWhite)
                        }
                    }
                }
            }
        }

        // Overlay UI
        Column(modifier = Modifier.fillMaxSize()) {
            // Top Bar
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 48.dp, start = 24.dp, end = 24.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    color = SaayaEmergencyRed,
                    shape = CircleShape
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Warning, null, tint = SaayaWhite, modifier = Modifier.size(16.dp))
                        Text(" LIVE SOS ", color = SaayaWhite, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }

                Column {
                    Text(connection, color = SaayaWhite.copy(alpha = 0.7f), fontSize = 12.sp)
                    state.microphoneError?.let { Text(it, color = SaayaEmergencyRed, fontSize = 12.sp) }
                }

                IconButton(onClick = onBack) {
                    Icon(Icons.Default.Close, "Close", tint = SaayaWhite)
                }
            }

            if (state.role == "viewer" && showChat) {
                ViewerMicrophoneControl(
                    microphoneEnabled = state.microphoneEnabled,
                    enabled = state.joined && !state.microphoneBusy && !microphonePermissionPending &&
                        (state.microphoneEnabled || connection == "Connected"),
                    busy = state.microphoneBusy,
                    permissionPending = microphonePermissionPending,
                    onClick = {
                        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                        if (!state.microphoneEnabled && !granted) {
                            microphonePermissionPending = true
                            microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
                        } else {
                            model.setViewerMicrophoneEnabled(!state.microphoneEnabled, granted)
                        }
                    },
                    modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 12.dp)
                )
            }

            Spacer(Modifier.weight(1f))

            // Bottom Controls (Visible only when chat is hidden)
            if (!showChat && !showParticipants) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.4f))
                        .padding(vertical = 32.dp, horizontal = 24.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = { showChat = true },
                        colors = IconButtonDefaults.iconButtonColors(containerColor = SaayaWhite.copy(alpha = 0.2f))
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Chat, "Chat", tint = SaayaWhite)
                    }

                    val isPublisher = state.role == "publisher"
                    IconButton(
                        onClick = {
                            if (isPublisher) onEndIncident()
                            model.leave()
                            onBack()
                        },
                        modifier = Modifier.size(72.dp),
                        colors = IconButtonDefaults.iconButtonColors(containerColor = SaayaEmergencyRed)
                    ) {
                        Icon(
                            imageVector = Icons.Default.CallEnd,
                            contentDescription = if (isPublisher) "End Call" else "Leave Call",
                            tint = SaayaWhite,
                            modifier = Modifier.size(32.dp)
                        )
                    }

                    if (isPublisher) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            IconButton(
                                onClick = { showParticipants = true; model.refreshViewers() },
                                colors = IconButtonDefaults.iconButtonColors(containerColor = SaayaWhite.copy(alpha = 0.2f))
                            ) {
                                Icon(Icons.Default.People, "Who's Joined", tint = SaayaWhite)
                            }
                            Text("Who's Joined (${viewers.size})", color = SaayaWhite, fontSize = 12.sp)
                        }
                    } else if (state.role == "viewer") {
                        ViewerMicrophoneControl(
                            microphoneEnabled = state.microphoneEnabled,
                            enabled = state.joined && !state.microphoneBusy && !microphonePermissionPending &&
                                (state.microphoneEnabled || connection == "Connected"),
                            busy = state.microphoneBusy,
                            permissionPending = microphonePermissionPending,
                            onClick = {
                                val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                                if (!state.microphoneEnabled && !granted) {
                                    microphonePermissionPending = true
                                    microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
                                } else {
                                    model.setViewerMicrophoneEnabled(!state.microphoneEnabled, granted)
                                }
                            }
                        )
                    }
                }
            }
        }

        // Expandable Chat Panel
        AnimatedVisibility(
            visible = showChat,
            enter = slideInVertically(initialOffsetY = { it }),
            exit = slideOutVertically(targetOffsetY = { it }),
            modifier = Modifier.align(Alignment.BottomCenter).imePadding().navigationBarsPadding()
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.6f),
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                color = MaterialTheme.colorScheme.surface
            ) {
                Column(Modifier.fillMaxSize()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Incident Chat", fontWeight = FontWeight.Bold)
                        IconButton(onClick = { showChat = false }) {
                            Icon(Icons.Default.Close, "Hide Chat")
                        }
                    }
                    HorizontalDivider()
                    IncidentChatContent(
                        messages = state.chatMessages,
                        status = state.chatStatus,
                        error = state.chatError,
                        connected = state.chatConnected,
                        denied = state.chatDenied,
                        readOnly = state.chatReadOnly,
                        sending = state.isSending,
                        hasOlder = state.hasOlderMessages,
                        olderLoading = state.olderMessagesLoading,
                        draft = state.chatDraft,
                        onDraftChange = { model.setDraft(it) },
                        onSend = { model.sendMessage() },
                        onLoadOlder = { model.loadOlderMessages(incidentId) },
                        userId = userId,
                        modifier = Modifier.weight(1f).padding(horizontal = 16.dp)
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = showParticipants && state.role == "publisher",
            enter = slideInVertically(initialOffsetY = { it }),
            exit = slideOutVertically(targetOffsetY = { it }),
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding()
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth().fillMaxHeight(0.6f),
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                color = MaterialTheme.colorScheme.surface
            ) {
                Column(Modifier.fillMaxSize()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Who's Joined (${viewers.size})", fontWeight = FontWeight.Bold)
                        IconButton(onClick = { showParticipants = false }) {
                            Icon(Icons.Default.Close, "Hide participants")
                        }
                    }
                    HorizontalDivider()
                    if (viewers.isEmpty()) Text("No authorized viewers connected.", Modifier.padding(16.dp))
                    LazyColumn(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                        items(viewers, key = { it.identity }) { viewer ->
                            ListItem(
                                headlineContent = { Text(viewer.name) },
                                supportingContent = { Text("Connected viewer") },
                                leadingContent = { Icon(Icons.Default.People, contentDescription = null) }
                            )
                        }
                    }
                }
            }
        }

        // Permission check overlay
        if (!state.loading && !state.joined && !state.ended && state.role == "publisher" && !permissionsGranted()) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.8f)), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                    Text("Permissions Required", color = SaayaWhite, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text("Saaya needs camera and microphone access for emergency calls.",
                        color = SaayaWhite.copy(alpha = 0.8f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    TextButton(onClick = {
                        permissions.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
                    }) {
                        Text("Grant Permissions", color = SaayaWhite, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun ViewerMicrophoneControl(
    microphoneEnabled: Boolean,
    enabled: Boolean,
    busy: Boolean,
    permissionPending: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.size(56.dp),
            colors = IconButtonDefaults.iconButtonColors(
                containerColor = if (microphoneEnabled) SaayaEmergencyRed else SaayaWhite,
                contentColor = if (microphoneEnabled) SaayaWhite else Color.Black,
                disabledContainerColor = SaayaWhite.copy(alpha = 0.2f),
                disabledContentColor = SaayaWhite.copy(alpha = 0.6f)
            )
        ) {
            Icon(
                if (microphoneEnabled) Icons.Default.Mic else Icons.Default.MicOff,
                contentDescription = if (microphoneEnabled) "Mute microphone" else "Unmute microphone"
            )
        }
        Text(
            when {
                permissionPending -> "Allow microphone"
                busy -> "Updating mic..."
                microphoneEnabled -> "Mute mic"
                else -> "Unmute mic"
            },
            color = SaayaWhite,
            fontSize = 12.sp
        )
    }
}

@Composable
private fun LiveSafetyVideo(room: Room, track: VideoTrack, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val renderer = remember(room) { SurfaceViewRenderer(context).also { room.initVideoRenderer(it) } }
    DisposableEffect(track, renderer) {
        track.addRenderer(renderer)
        onDispose { track.removeRenderer(renderer) }
    }
    DisposableEffect(renderer) { onDispose { renderer.release() } }
    AndroidView(factory = { renderer }, modifier = modifier)
}
