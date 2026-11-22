package com.project.saaya.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.project.saaya.data.model.Incident
import com.project.saaya.data.repository.LiveSafetyRepository
import kotlinx.coroutines.CancellationException

@Composable
fun LiveSafetyListScreen(repository: LiveSafetyRepository, onBack: () -> Unit, onOpen: (String) -> Unit) {
    var incidents by remember { mutableStateOf(emptyList<Incident>()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableStateOf(0) }
    LaunchedEffect(repository, retry) {
        loading = true
        error = null
        incidents = emptyList()
        try { incidents = repository.activeIncidents() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { error = "Could not load live safety sessions. Check your connection and retry." }
        finally { loading = false }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton(onClick = onBack) { Text("Back") }
        Text("Active live safety sessions")
        Text("Only your incidents and incidents shared through current Trusted Circle links appear here.")
        if (loading) CircularProgressIndicator()
        error?.let { Text(it) }
        TextButton(onClick = { retry++ }, enabled = !loading) { Text("Refresh") }
        if (!loading && error == null && incidents.isEmpty()) Text("No active shared SOS incidents.")
        incidents.forEach { incident ->
            Text("Incident: ${incident.id}")
            Text("Started: ${incident.startedAt}")
            TextButton(onClick = { onOpen(incident.id) }) { Text("Open live safety") }
        }
    }
}
