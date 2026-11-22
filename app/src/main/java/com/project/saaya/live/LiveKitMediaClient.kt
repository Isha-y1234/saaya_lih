package com.project.saaya.live

import android.content.Context
import com.project.saaya.ui.state.SafetyMedia
import io.livekit.android.ConnectOptions
import io.livekit.android.LiveKit
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import io.livekit.android.room.Room
import io.livekit.android.room.track.VideoTrack
import io.livekit.android.room.track.Track
import io.livekit.android.util.LoggingLevel
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

class LiveKitMediaClient(private val context: Context) : SafetyMedia {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var events: Job? = null
    private var viewer = false
    private val microphoneState = MutableStateFlow(false)
    override val microphoneEnabled = microphoneState.asStateFlow()
    private val connectedIdentities = MutableStateFlow<Set<String>>(emptySet())
    val participants = connectedIdentities.asStateFlow()
    private val currentRoom = MutableStateFlow<Room?>(null)
    val room = currentRoom.asStateFlow()
    private val currentVideo = MutableStateFlow<VideoTrack?>(null)
    val video = currentVideo.asStateFlow()
    private val connectionState = MutableStateFlow("Disconnected")
    val connection = connectionState.asStateFlow()

    override suspend fun connect(url: String, token: String, video: Boolean, audio: Boolean) {
        disconnect()
        LiveKit.loggingLevel = LoggingLevel.OFF
        LiveKit.enableWebRTCLogging = false
        val room = LiveKit.create(context.applicationContext)
        currentRoom.value = room
        viewer = !video
        connectionState.value = "Connecting"
        events = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            room.events.collect { event ->
                when (event) {
                    is RoomEvent.Reconnecting -> connectionState.value = "Reconnecting"
                    is RoomEvent.Reconnected -> connectionState.value = "Connected"
                    is RoomEvent.Disconnected -> connectionState.value = "Disconnected"
                    else -> Unit
                }
                
                updateParticipantState(room)
                // Track update logic:
                // If I am the publisher (sender), show my local video.
                // If I am the subscriber (recipient), show the remote video from the sender.
                val localVideo = room.localParticipant.trackPublications.values
                    .mapNotNull { it.track as? VideoTrack }.firstOrNull()
                val remoteVideo = room.remoteParticipants.values.flatMap { it.trackPublications.values }
                    .mapNotNull { it.track as? VideoTrack }.firstOrNull()
                
                currentVideo.value = localVideo ?: remoteVideo
            }
        }
        room.connect(url, token, options = ConnectOptions(autoSubscribe = true))
        
        if (audio) {
            check(room.localParticipant.setMicrophoneEnabled(true)) { "Microphone could not start" }
        }
        if (video) {
            check(room.localParticipant.setCameraEnabled(true)) { "Camera could not start" }
            currentVideo.value = room.localParticipant.trackPublications.values
                .mapNotNull { it.track as? VideoTrack }.firstOrNull()
        }
        updateParticipantState(room)
        connectionState.value = "Connected"
    }

    private fun updateParticipantState(room: Room) {
        if (currentRoom.value !== room) return
        if (connectionState.value == "Disconnected") {
            microphoneState.value = false
            connectedIdentities.value = emptySet()
        } else {
            microphoneState.value = room.localParticipant.trackPublications.values.any {
                it.source == Track.Source.MICROPHONE && !it.muted
            }
            connectedIdentities.value = if (connectionState.value == "Reconnecting") emptySet()
                else room.remoteParticipants.keys.map { it.value }.toSet()
        }
    }

    override suspend fun setViewerMicrophoneEnabled(enabled: Boolean) {
        val room = checkNotNull(currentRoom.value) { "No live session" }
        check(viewer) { "Viewer microphone control only" }
        if (enabled) {
            // Existing sessions receive permission updates over signaling; do not reconnect.
            withTimeout(5000) {
                while (true) {
                    check(currentRoom.value === room && viewer)
                    val permissions = room.localParticipant.permissions
                    if (permissions?.canPublish == true &&
                        Track.Source.MICROPHONE in permissions.canPublishSources &&
                        Track.Source.CAMERA !in permissions.canPublishSources) break
                    delay(50)
                }
            }
        }
        try {
            check(room.localParticipant.setMicrophoneEnabled(enabled)) { "Microphone change failed" }
        } finally {
            updateParticipantState(room)
        }
    }

    override fun disconnect() {
        viewer = false
        microphoneState.value = false
        connectedIdentities.value = emptySet()
        events?.cancel()
        events = null
        currentVideo.value = null
        currentRoom.value?.let { it.disconnect(); it.release() }
        currentRoom.value = null
        connectionState.value = "Disconnected"
    }

    override fun release() { disconnect(); scope.cancel() }
}
