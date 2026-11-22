package com.project.saaya.ui.state

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.project.saaya.data.repository.*
import com.project.saaya.data.remote.LiveSafetyViewer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import retrofit2.HttpException

fun connectedViewers(identities: Set<String>, authorized: List<LiveSafetyViewer>): List<LiveSafetyViewer> =
    authorized.filter { it.identity in identities }.distinctBy { it.identity }.sortedBy { it.name }

interface SafetyMedia {
    val microphoneEnabled: StateFlow<Boolean>
    suspend fun connect(url: String, token: String, video: Boolean, audio: Boolean)
    suspend fun setViewerMicrophoneEnabled(enabled: Boolean)
    fun disconnect()
    fun release()
}

data class LiveSafetyState(
    val loading: Boolean = false,
    val joined: Boolean = false,
    val role: String? = null,
    val ended: Boolean = false,
    val error: String? = null,
    val chatConnected: Boolean = false,
    val chatMessages: List<IncidentMessage> = emptyList(),
    val chatStatus: String = "Connecting to chat...",
    val chatError: String? = null,
    val chatReadOnly: Boolean = false,
    val isSending: Boolean = false,
    val hasOlderMessages: Boolean = true,
    val olderMessagesLoading: Boolean = false,
    val chatDraft: String = "",
    val viewers: List<LiveSafetyViewer> = emptyList(),
    val microphoneEnabled: Boolean = false,
    val microphoneBusy: Boolean = false,
    val microphoneError: String? = null,
    val chatDenied: Boolean = false
)

class LiveSafetyViewModel(
    private val repository: LiveSafetyRepository,
    private val chatRepository: IncidentChatRepository,
    val media: SafetyMedia,
    private val onSessionEnded: (String) -> Unit = {}
) : ViewModel() {
    private val state = MutableStateFlow(LiveSafetyState())
    val uiState: StateFlow<LiveSafetyState> = state.asStateFlow()
    private var connection: Job? = null
    private var chatJob: Job? = null
    private var microphoneJob: Job? = null
    private var viewerRefresh: Job? = null
    private var currentIncidentId: String? = null

    init {
        viewModelScope.launch {
            media.microphoneEnabled.collect { enabled ->
                state.value = state.value.copy(microphoneEnabled = enabled)
            }
        }
    }

    fun refreshViewers() {
        val id = currentIncidentId ?: return
        if (!state.value.joined || state.value.role != "publisher") return
        viewerRefresh?.cancel()
        viewerRefresh = viewModelScope.launch {
            try {
                val status = repository.status(id)
                if (currentIncidentId == id && state.value.joined) {
                    state.value = state.value.copy(viewers = if (status.active && status.role == "publisher") status.viewers.orEmpty() else emptyList())
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { state.value = state.value.copy(viewers = emptyList()) }
        }
    }

    fun join(incidentId: String, publishingPermission: Boolean, rejoinOnly: Boolean = false) {
        if (state.value.loading || (state.value.joined && currentIncidentId == incidentId)) return
        currentIncidentId = incidentId
        connection?.cancel()
        state.value = state.value.copy(loading = true, error = null, ended = false)
        connection = viewModelScope.launch {
            try {
                val status = try {
                    repository.status(incidentId)
                } catch (e: HttpException) {
                    if (e.code() in listOf(401, 403, 404, 409, 410)) {
                        handleSessionEnd(incidentId)
                        return@launch
                    }
                    throw e
                }

                if (!status.active || (rejoinOnly && !status.sessionActive)) {
                    handleSessionEnd(incidentId)
                    return@launch
                }

                state.value = state.value.copy(role = status.role)
                if (status.role == "publisher" && !publishingPermission) {
                    state.value = state.value.copy(
                        loading = false,
                        error = "Allow camera and microphone to participate in the emergency SOS."
                    )
                    return@launch
                }
                val credential = if (rejoinOnly) repository.rejoin(incidentId) else repository.join(incidentId)
                require(credential.role == status.role)
                media.connect(
                    credential.url, credential.token,
                    video = credential.role == "publisher",
                    audio = credential.role == "publisher"
                )
                state.value = state.value.copy(loading = false, joined = true, role = status.role,
                    microphoneEnabled = media.microphoneEnabled.value, microphoneBusy = false, microphoneError = null,
                    viewers = if (status.role == "publisher") status.viewers.orEmpty() else emptyList())

                // Connect Chat
                connectChat(incidentId)

                while (true) {
                    delay(5000)
                    val current = try {
                        repository.status(incidentId)
                    } catch (e: HttpException) {
                        if (e.code() in listOf(401, 403, 404, 409, 410)) {
                            handleSessionEnd(incidentId)
                            return@launch
                        }
                        throw e
                    }

                    if (!current.active || (rejoinOnly && !current.sessionActive)) {
                        handleSessionEnd(incidentId)
                        return@launch
                    }
                    state.value = state.value.copy(viewers = if (current.role == "publisher") current.viewers.orEmpty() else emptyList())
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                media.disconnect()
                if (failure is HttpException && failure.code() in listOf(401, 403, 404, 409)) {
                    handleSessionEnd(incidentId)
                } else {
                    state.value = state.value.copy(
                        loading = false, joined = false,
                        error = "Live SOS is unavailable. Check your connection and retry."
                    )
                }
            }
        }
    }

    private fun handleSessionEnd(incidentId: String) {
        microphoneJob?.cancel()
        viewerRefresh?.cancel()
        media.disconnect()
        chatJob?.cancel()
        onSessionEnded(incidentId)
        state.value = state.value.copy(ended = true, joined = false, loading = false, error = null, chatConnected = false, viewers = emptyList())
    }

    private fun connectChat(id: String) {
        chatJob?.cancel()
        chatJob = viewModelScope.launch {
            while (true) {
                try {
                    chatRepository.connect(id, state.value.chatMessages.lastOrNull()?.id).collect { frame ->
                        when (frame.type) {
                            "denied" -> {
                                state.value = state.value.copy(
                                    chatStatus = "Chat unavailable.",
                                    chatDenied = true,
                                    chatReadOnly = true,
                                    chatConnected = false
                                )
                                return@collect
                            }
                            "history", "messages" -> {
                                val newMessages = mergeChatMessages(state.value.chatMessages, frame.messages.orEmpty(), id)
                                state.value = state.value.copy(
                                    chatConnected = true,
                                    chatReadOnly = frame.readOnly,
                                    chatStatus = if (frame.readOnly) "Incident ended · Read-only" else "Connected",
                                    chatMessages = newMessages,
                                    hasOlderMessages = if (frame.type == "history") frame.messages.orEmpty().size == 100 else state.value.hasOlderMessages
                                )
                            }
                            "sent" -> {
                                state.value = state.value.copy(isSending = false, chatError = null, chatDraft = "")
                            }
                            "error" -> {
                                state.value = state.value.copy(isSending = false, chatError = frame.detail)
                                if (frame.readOnly) state.value = state.value.copy(chatReadOnly = true)
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                } finally {
                    state.value = state.value.copy(
                        chatConnected = false,
                        isSending = false,
                        chatError = if (state.value.isSending) "Delivery uncertain. Check history before sending again." else state.value.chatError
                    )
                }
                if (state.value.chatDenied) break
                state.value = state.value.copy(chatStatus = "Reconnecting chat...")
                delay(3000)
            }
        }
    }

    fun setViewerMicrophoneEnabled(enabled: Boolean, permissionGranted: Boolean) {
        val current = state.value
        val id = currentIncidentId ?: return
        if (!current.joined || current.role != "viewer" || current.microphoneBusy) return
        if (enabled && !permissionGranted) {
            state.value = current.copy(microphoneError = "Allow microphone access to unmute.")
            return
        }
        state.value = current.copy(microphoneBusy = true, microphoneError = null)
        microphoneJob = viewModelScope.launch {
            try {
                if (enabled) repository.enableViewerAudio(id)
                media.setViewerMicrophoneEnabled(enabled)
                state.value = state.value.copy(microphoneEnabled = media.microphoneEnabled.value)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                state.value = state.value.copy(microphoneError = "Could not change microphone. Please retry.")
            } finally {
                state.value = state.value.copy(microphoneBusy = false)
            }
        }
    }

    fun setDraft(text: String) {
        if (text.length <= 2000) {
            state.value = state.value.copy(chatDraft = text)
        }
    }

    fun sendMessage() {
        val text = state.value.chatDraft
        if (text.isBlank() || !state.value.chatConnected || state.value.isSending ||
            state.value.chatReadOnly || state.value.chatDenied) return
        state.value = state.value.copy(isSending = true)
        val success = chatRepository.send(text)
        if (!success) {
            state.value = state.value.copy(isSending = false, chatError = "Failed to send. Reconnecting...")
        }
    }

    fun loadOlderMessages(incidentId: String) {
        if (state.value.olderMessagesLoading || !state.value.hasOlderMessages || state.value.chatMessages.isEmpty()) return
        state.value = state.value.copy(olderMessagesLoading = true)
        viewModelScope.launch {
            try {
                val page = chatRepository.older(incidentId, state.value.chatMessages.first().id)
                val merged = mergeChatMessages(state.value.chatMessages, page.messages, incidentId)
                state.value = state.value.copy(
                    chatMessages = merged,
                    hasOlderMessages = page.nextBefore != null,
                    olderMessagesLoading = false
                )
            } catch (e: Exception) {
                state.value = state.value.copy(olderMessagesLoading = false, chatError = "Could not load older messages.")
            }
        }
    }

    fun leave() {
        connection?.cancel()
        chatJob?.cancel()
        microphoneJob?.cancel()
        microphoneJob = null
        viewerRefresh?.cancel()
        viewerRefresh = null
        connection = null
        chatJob = null
        media.disconnect()
        state.value = LiveSafetyState()
        currentIncidentId = null
    }

    override fun onCleared() {
        leave()
        media.release()
    }
}
