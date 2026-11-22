package com.project.saaya.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.project.saaya.data.repository.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun IncidentChatScreen(id: String, userId: String, repository: IncidentChatRepository, onBack: () -> Unit,
                       onEvidence: () -> Unit = {}, onReports: () -> Unit = {}) {
    var messages by remember(id, userId) { mutableStateOf(emptyList<IncidentMessage>()) }
    var draft by remember(id, userId) { mutableStateOf("") }
    var connected by remember(id, userId) { mutableStateOf(false) }
    var readOnly by remember(id, userId) { mutableStateOf(false) }
    var sending by remember(id, userId) { mutableStateOf(false) }
    var denied by remember(id, userId) { mutableStateOf(false) }
    var status by remember(id, userId) { mutableStateOf("Loading chat…") }
    var error by remember(id, userId) { mutableStateOf<String?>(null) }
    var olderLoading by remember(id, userId) { mutableStateOf(false) }
    var hasOlder by remember(id, userId) { mutableStateOf(true) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()

    LaunchedEffect(id, userId, repository, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (!denied) {
                try {
                    repository.connect(id, messages.lastOrNull()?.id).collect { frame ->
                        when (frame.type) {
                            "denied" -> {
                                denied = true
                                messages = emptyList()
                                draft = ""
                                status = "Chat is unavailable for this account."
                            }
                            "history", "messages" -> {
                                connected = true
                                readOnly = frame.readOnly
                                status = if (readOnly) "Incident ended · Read-only" else "Connected"
                                messages = mergeChatMessages(messages, frame.messages.orEmpty(), id)
                                if (frame.type == "history") hasOlder = frame.messages.orEmpty().size == 100
                            }
                            "sent" -> {
                                sending = false
                                draft = ""
                                error = null
                            }
                            "error" -> {
                                sending = false
                                error = frame.detail
                                if (frame.readOnly) readOnly = true
                            }
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    error = "Could not connect to chat."
                } finally {
                    connected = false
                    if (sending) error = "Delivery uncertain. Check history before sending again."
                    sending = false
                }
                if (!denied) {
                    status = "Connection lost · Reconnecting…"
                    delay(3000)
                }
            }
        }
    }

    Column(Modifier.fillMaxSize().imePadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = onBack) { Text("Back") }
        Text("Incident chat", style = MaterialTheme.typography.headlineSmall)
        TextButton(onClick = onEvidence) { Text("Emergency evidence") }
        TextButton(onClick = onReports) { Text("Incident Reports") }

        IncidentChatContent(
            messages = messages,
            status = status,
            error = error,
            connected = connected,
            denied = denied,
            readOnly = readOnly,
            sending = sending,
            hasOlder = hasOlder,
            olderLoading = olderLoading,
            draft = draft,
            onDraftChange = { draft = it },
            onSend = {
                sending = repository.send(draft)
                if (!sending) error = "Disconnected. Please wait for reconnection."
            },
            onLoadOlder = {
                olderLoading = true
                scope.launch {
                    try {
                        val page = repository.older(id, messages.first().id)
                        messages = mergeChatMessages(messages, page.messages, id)
                        hasOlder = page.nextBefore != null
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        error = "Could not load older messages."
                    } finally {
                        olderLoading = false
                    }
                }
            },
            userId = userId,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
fun IncidentChatContent(
    messages: List<IncidentMessage>,
    status: String,
    error: String?,
    connected: Boolean,
    denied: Boolean,
    readOnly: Boolean,
    sending: Boolean,
    hasOlder: Boolean,
    olderLoading: Boolean,
    draft: String,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onLoadOlder: () -> Unit,
    userId: String,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(status, style = MaterialTheme.typography.labelSmall)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

        if (!connected && !denied) LinearProgressIndicator(Modifier.fillMaxWidth())

        val listState = androidx.compose.foundation.lazy.rememberLazyListState()
        LaunchedEffect(messages.lastOrNull()?.id) {
            if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
        }

        LazyColumn(Modifier.weight(1f), state = listState, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (messages.isEmpty() && connected) {
                item {
                    Box(Modifier.fillParentMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                        Text("No messages yet.")
                    }
                }
            }
            if (hasOlder && !denied && messages.isNotEmpty()) {
                item {
                    TextButton(
                        enabled = !olderLoading,
                        onClick = onLoadOlder,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (olderLoading) "Loading…" else "Load older messages")
                    }
                }
            }

            items(messages, key = { it.id }) { message ->
                ChatBubble(message = message, isMe = message.senderId == userId)
            }
        }

        if (readOnly) {
            Text("This incident has ended. You can view its chat history.", style = MaterialTheme.typography.bodySmall)
        } else if (!denied) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { if (it.length <= 2000) onDraftChange(it) },
                    enabled = !sending,
                    placeholder = { Text("Message") },
                    modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = {
                        if (connected && !sending && draft.isNotBlank()) onSend()
                    }),
                    maxLines = 4
                )
                Spacer(Modifier.width(8.dp))
                Button(
                    enabled = connected && !sending && draft.isNotBlank(),
                    onClick = onSend
                ) {
                    Text(if (sending) "…" else "Send")
                }
            }
        }
    }
}

@Composable
private fun ChatBubble(message: IncidentMessage, isMe: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isMe) Arrangement.End else Arrangement.Start
    ) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (isMe) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
            ),
            modifier = Modifier.fillMaxWidth(0.85f)
        ) {
            Column(Modifier.padding(12.dp)) {
                Text(
                    text = if (isMe) "You" else "Trusted participant",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isMe) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(message.text)
                Text(
                    text = message.createdAt,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = androidx.compose.ui.text.style.TextAlign.End
                )
            }
        }
    }
}
